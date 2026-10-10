package com.agentjava.permission;

import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolCategory;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 权限检查器：把「五层防御」串成一条决策链的门面与实现。
 *
 * <p>设计要点：
 * <ul>
 *   <li>核心逻辑全部内联在本类，路径沙箱 / 规则引擎 / 危险命令检测都是私有方法，不拆独立类；</li>
 *   <li>判定结果用 {@link CheckResult}（携带 reason），三种静态工厂 {@code allow/deny/ask} 简化构造；</li>
 *   <li>规则文件按 mtime + size 缓存解析结果，文件不变就不重解析（SnakeYAML 解析有开销）；</li>
 *   <li>被拒绝不终止循环——返回 isError 的工具结果，模型下一轮能看到并自行调整策略。</li>
 * </ul>
 *
 * <p>职责范围（应用层五层 + HITL）：危险命令黑名单、路径沙箱、规则引擎、权限模式、敏感路径禁写。
 * OS 级沙箱（seatbelt / bubblewrap）暂不实现（无对应平台二进制依赖）。
 */
public class PermissionChecker {

    /** 单次判定的结果，携带理由字符串。 */
    public record CheckResult(PermissionMode.Decision decision, String reason) {
        public static CheckResult allow() {
            return new CheckResult(PermissionMode.Decision.ALLOW, "");
        }

        public static CheckResult deny(String reason) {
            return new CheckResult(PermissionMode.Decision.DENY, reason);
        }

        public static CheckResult ask() {
            return new CheckResult(PermissionMode.Decision.ASK, "");
        }
    }

    // ===== Layer 1：安全命令白名单（仅 Bash，命中即放行，跳过后续确认） =====

    private static final Set<String> SAFE_COMMANDS = Set.of(
            "ls", "dir", "pwd", "echo", "cat", "head", "tail", "wc",
            "which", "whoami", "date", "uname", "env", "printenv",
            "git status", "git log", "git diff", "git show", "git branch",
            "java -version", "java --version", "python3 --version", "node --version"
    );

    // ===== Layer 2：危险命令黑名单（仅 Bash，命中即硬拒绝） =====

    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            Pattern.compile("rm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+/\\s*$"),
            Pattern.compile("mkfs\\."),
            Pattern.compile("dd\\s+if=.*of=/dev/"),
            Pattern.compile("chmod\\s+-R\\s+777\\s+/"),
            Pattern.compile(":\\(\\)\\{\\s*:\\|:&\\s*\\};:"),
            Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),
            Pattern.compile("wget\\s+.*\\|\\s*(ba)?sh"),
            Pattern.compile(">\\s*/dev/sd")
    );

    // ===== 敏感路径禁写（应用层兜底，与 OS 沙箱双重防护） =====
    // 适配工程约定：配置目录是 .agentjava/

    private static final List<String> DEFAULT_DENY_WRITE = List.of(
            ".agentjava/config.yaml",
            ".agentjava/permissions.local.yaml",
            ".agentjava/skills/"
    );

    /** 工具名 → 用于规则匹配 / 描述生成的内容字段。 */
    private static final Map<String, String> CONTENT_FIELDS = Map.of(
            "Bash", "command",
            "ReadFile", "file_path",
            "WriteFile", "file_path",
            "EditFile", "file_path",
            "Glob", "pattern",
            "Grep", "pattern"
    );

    // ===== 状态字段 =====

    private volatile PermissionMode mode;
    private final Path projectRoot;
    private final List<String> denyWrite;

    /** 规则文件解析缓存：路径 → (mtime, size, 解析结果)。loadRules 整体 synchronized。 */
    private final Map<Path, CacheEntry> ruleCache = new ConcurrentHashMap<>();

    public PermissionChecker(PermissionMode mode, Path projectRoot) {
        this.mode = mode == null ? PermissionMode.DEFAULT : mode;
        this.projectRoot = projectRoot;

        var resolved = new ArrayList<String>();
        if (projectRoot != null) {
            for (String rel : DEFAULT_DENY_WRITE) {
                resolved.add(projectRoot.resolve(rel).toAbsolutePath().normalize().toString());
            }
        }
        this.denyWrite = resolved;
    }

    public void setMode(PermissionMode mode) {
        this.mode = mode == null ? PermissionMode.DEFAULT : mode;
    }

    public PermissionMode getMode() {
        return mode;
    }

    // ===== 主流程：六层决策链 =====

    public CheckResult check(Tool tool, Map<String, Object> args) {
        String toolName = tool.name();
        ToolCategory category = tool.category();
        String content = extractContent(toolName, args);

        // Layer 0：Plan Mode 例外（只读 + 只允许写计划文件，其余一律拒绝）
        if (mode == PermissionMode.PLAN) {
            if (category == ToolCategory.READ) {
                return CheckResult.allow();
            }
            if (isWritePathTool(toolName) && content != null && content.contains(".agentjava/plans/")) {
                return CheckResult.allow();
            }
            // 计划模式下任何写 / 命令动作都被拒，需退出 Plan Mode 才能执行
            return CheckResult.deny("Plan mode is read-only; refusing: " + describeToolAction(toolName, args));
        }

        // BYPASS 模式：仅保留黑名单 + 敏感路径禁写，其余全放行（危险模式，仅受控环境）
        if (mode == PermissionMode.BYPASS) {
            if (isBash(toolName) && isDangerous(content)) {
                return CheckResult.deny("Dangerous command detected (bypass mode still blocks): " + content);
            }
            if (isWritePathTool(toolName) && isDeniedPath(content)) {
                return CheckResult.deny("Protected path (bypass mode still blocks): " + content);
            }
            return CheckResult.allow();
        }

        // Layer 1：安全命令白名单（仅 Bash）
        if (isBash(toolName) && content != null && isSafeCommand(content)) {
            return CheckResult.allow();
        }

        // Layer 2：危险命令黑名单（仅 Bash）
        if (isBash(toolName) && content != null) {
            for (var p : DANGEROUS_PATTERNS) {
                if (p.matcher(content).find()) {
                    return CheckResult.deny("Dangerous command detected: " + p.pattern());
                }
            }
        }

        // Layer 2b：敏感路径禁写（仅写文件工具）
        if (isWritePathTool(toolName) && content != null && isDeniedPath(content)) {
            return CheckResult.deny("Path is protected by sandbox: " + content);
        }

        // Layer 3：路径沙箱（仅文件工具，越界需用户确认）
        if (isPathTool(toolName) && content != null && !isPathAllowed(content)) {
            return CheckResult.ask();
        }

        // Layer 4：规则引擎（三份文件合并，deny > ask > allow）
        PermissionRule hit = evaluateRules(loadRules(), toolName, content);
        if (hit != null) {
            return switch (hit.effect()) {
                case DENY -> CheckResult.deny("Denied by rule: " + hit.toolName() + "(" + hit.pattern() + ")");
                case ASK -> CheckResult.ask();
                case ALLOW -> CheckResult.allow();
            };
        }

        // Layer 5：权限模式兜底
        PermissionMode.Decision d = mode.decide(category);
        return switch (d) {
            case ALLOW -> CheckResult.allow();
            case DENY -> CheckResult.deny("Denied by permission mode: " + mode);
            case ASK -> CheckResult.ask();
        };
    }

    // ===== 内容提取 =====

    /** 从工具入参里抽取「用于规则匹配 / 描述生成」的内容；不需要内容级匹配的工具返回 null。 */
    public String contentOf(String toolName, Map<String, Object> args) {
        return extractContent(toolName, args);
    }

    private static String extractContent(String toolName, Map<String, Object> args) {
        String field = CONTENT_FIELDS.get(toolName);
        if (field == null || args == null) {
            return null;
        }
        var v = args.get(field);
        return v instanceof String s ? s : null;
    }

    // ===== Layer 1 辅助 =====

    private boolean isSafeCommand(String command) {
        String trimmed = command.trim();
        // 含元字符的命令不走白名单（管道 / 重定向 / 子 shell 风险）
        if (trimmed.contains("|") || trimmed.contains(";") || trimmed.contains("&&")
                || trimmed.contains(">") || trimmed.contains("$(") || trimmed.contains("`")) {
            return false;
        }
        for (var safe : SAFE_COMMANDS) {
            if (trimmed.equals(safe) || trimmed.startsWith(safe + " ")) {
                return true;
            }
        }
        return false;
    }

    // ===== Layer 2 辅助 =====

    private boolean isDangerous(String content) {
        if (content == null) {
            return false;
        }
        for (var p : DANGEROUS_PATTERNS) {
            if (p.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    // ===== Layer 2b / 3 路径辅助 =====

    private static boolean isPathTool(String toolName) {
        return "ReadFile".equals(toolName) || "WriteFile".equals(toolName) || "EditFile".equals(toolName);
    }

    private static boolean isWritePathTool(String toolName) {
        return "WriteFile".equals(toolName) || "EditFile".equals(toolName);
    }

    private static boolean isBash(String toolName) {
        return "Bash".equals(toolName);
    }

    /** 文件操作是否落在允许范围内（项目根目录或系统临时目录）。失败则 fail-open 放行。 */
    private boolean isPathAllowed(String pathStr) {
        if (projectRoot == null) {
            return true;
        }
        try {
            Path p = Path.of(pathStr).toAbsolutePath().normalize();
            Path root = projectRoot.toAbsolutePath().normalize();
            Path tmp = Path.of("/tmp").toAbsolutePath().normalize();
            return p.startsWith(root) || p.startsWith(tmp);
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isDeniedPath(String pathStr) {
        try {
            Path p = Path.of(pathStr).toAbsolutePath().normalize();
            for (String denied : denyWrite) {
                if (p.startsWith(Path.of(denied))) {
                    return true;
                }
            }
        } catch (Exception e) {
            // 解析失败不拦截
        }
        return false;
    }

    // ===== 工具描述（HITL 弹窗用） =====

    public String describeToolAction(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "Bash" -> "Execute: " + stringArg(args, "command", "");
            case "ReadFile" -> "Read: " + stringArg(args, "file_path", "");
            case "WriteFile" -> "Write: " + stringArg(args, "file_path", "");
            case "EditFile" -> "Edit: " + stringArg(args, "file_path", "");
            case "Glob" -> "Glob: " + stringArg(args, "pattern", "");
            case "Grep" -> "Grep: " + stringArg(args, "pattern", "");
            default -> toolName;
        };
    }

    // ===== Layer 4：规则引擎 =====

    private enum RuleEffect {
        ALLOW, DENY, ASK
    }

    private record PermissionRule(String toolName, String pattern, RuleEffect effect) {
        boolean matches(String toolName, String content) {
            if (!this.toolName.equals(toolName) || content == null) {
                return false;
            }
            // 路径类内容走 NIO glob；命令/其它内容退化为字符串 glob（支持 * ? 通配）
            try {
                if (FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Path.of(content))) {
                    return true;
                }
            } catch (Exception ignored) {
                // content 非合法路径（如 shell 命令），落到字符串 glob
            }
            return stringGlob(pattern, content);
        }
    }

    private record CacheEntry(long mtime, long size, List<PermissionRule> rules) {
    }

    /** 三份规则文件合并：用户级 → 项目级 → 本地级（不进版本控制）。裁决只看效果，不看来源。 */
    private synchronized List<PermissionRule> loadRules() {
        var rules = new ArrayList<PermissionRule>();
        Path userHome = Path.of(System.getProperty("user.home"));
        rules.addAll(rulesFor(userHome.resolve(".agentjava").resolve("permissions.yaml")));
        if (projectRoot != null) {
            rules.addAll(rulesFor(projectRoot.resolve(".agentjava").resolve("permissions.yaml")));
            rules.addAll(rulesFor(projectRoot.resolve(".agentjava").resolve("permissions.local.yaml")));
        }
        return rules;
    }

    private List<PermissionRule> rulesFor(Path path) {
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            var attrs = Files.readAttributes(path, "size,lastModifiedTime");
            long size = (long) attrs.get("size");
            long mtime = ((FileTime) attrs.get("lastModifiedTime")).to(TimeUnit.NANOSECONDS);
            CacheEntry cached = ruleCache.get(path);
            if (cached != null && cached.mtime == mtime && cached.size == size) {
                return cached.rules;
            }
            var parsed = loadRulesFile(path);
            ruleCache.put(path, new CacheEntry(mtime, size, parsed));
            return parsed;
        } catch (Exception e) {
            return loadRulesFile(path);
        }
    }

    private List<PermissionRule> loadRulesFile(Path path) {
        if (!Files.exists(path)) {
            return List.of();
        }
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return List.of();
        }
        Object parsed;
        try {
            parsed = new Yaml().load(content);
        } catch (Exception e) {
            return List.of();
        }
        if (!(parsed instanceof List<?> entries)) {
            return List.of();
        }
        var rules = new ArrayList<PermissionRule>();
        for (var e : entries) {
            if (!(e instanceof Map<?, ?> m)) {
                continue;
            }
            Object ro = m.get("rule");
            Object eo = m.get("effect");
            if (!(ro instanceof String rs) || !(eo instanceof String es)) {
                continue;
            }
            PermissionRule r = parseRule(rs, es);
            if (r != null) {
                rules.add(r);
            }
        }
        return rules;
    }

    private static PermissionRule parseRule(String ruleStr, String effectStr) {
        int open = ruleStr.indexOf('(');
        int close = ruleStr.lastIndexOf(')');
        if (open < 0 || close < open) {
            return null;
        }
        String tName = ruleStr.substring(0, open).trim();
        String pattern = ruleStr.substring(open + 1, close).trim();
        RuleEffect effect = switch (effectStr.trim().toLowerCase()) {
            case "allow" -> RuleEffect.ALLOW;
            case "ask" -> RuleEffect.ASK;
            case "deny" -> RuleEffect.DENY;
            default -> null;
        };
        if (effect == null) {
            return null;
        }
        return new PermissionRule(tName, pattern, effect);
    }

    /** deny > ask > allow：命中 deny 直接返回，ask 压过 allow。 */
    private static PermissionRule evaluateRules(List<PermissionRule> rules, String toolName, String content) {
        PermissionRule hit = null;
        for (PermissionRule rule : rules) {
            if (!rule.matches(toolName, content)) {
                continue;
            }
            if (rule.effect() == RuleEffect.DENY) {
                return rule;
            }
            if (rule.effect() == RuleEffect.ASK || hit == null) {
                hit = rule;
            }
        }
        return hit;
    }

    // ===== 动态追加规则（「始终允许」时调用） =====

    /**
     * 把一条 allow 规则追加到本地权限文件。用户点「始终允许」后调用，下次启动依然生效。
     *
     * @param toolName 工具名
     * @param pattern  规则 pattern（已由调用方泛化，如 "git *" 或 "/proj/src/*"）
     */
    public void appendLocalRule(String toolName, String pattern) {
        if (projectRoot == null) {
            return;
        }
        Path localFile = projectRoot.resolve(".agentjava").resolve("permissions.local.yaml");
        try {
            Files.createDirectories(localFile.getParent());
            var rules = new ArrayList<>(loadRulesFile(localFile));
            rules.add(new PermissionRule(toolName, pattern, RuleEffect.ALLOW));

            var entries = new ArrayList<Map<String, String>>();
            for (var r : rules) {
                entries.add(Map.of(
                        "rule", r.toolName() + "(" + r.pattern() + ")",
                        "effect", effectToStr(r.effect())
                ));
            }
            Files.writeString(localFile, new Yaml().dump(entries));
        } catch (IOException ignored) {
            // 写盘失败不影响本次工具执行
        }
    }

    /** 把一次「始终允许」泛化成一条规则 pattern：Bash 取首个命令词 + *，文件工具取父目录 + /*。 */
    public static String buildAlwaysAllowPattern(String toolName, String content) {
        if (content == null || content.isBlank()) {
            return "*";
        }
        if (isBash(toolName)) {
            String[] parts = content.trim().split("\\s+");
            return parts[0] + " *";
        }
        if (isPathTool(toolName)) {
            try {
                Path p = Path.of(content).toAbsolutePath().normalize();
                Path parent = p.getParent();
                if (parent != null) {
                    return parent.toString() + "/*";
                }
            } catch (Exception ignored) {
                // 落到下面
            }
            return content;
        }
        return content;
    }

    // ===== 小工具 =====

    private static String effectToStr(RuleEffect e) {
        return switch (e) {
            case ALLOW -> "allow";
            case ASK -> "ask";
            case DENY -> "deny";
        };
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        if (args == null) {
            return def;
        }
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    /** 简单的字符串 glob 匹配，支持 *（任意序列）与 ?（单字符）。 */
    private static boolean stringGlob(String pattern, String text) {
        var sb = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append(".");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return text.matches(sb.toString());
    }
}
