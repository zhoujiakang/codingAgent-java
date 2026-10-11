package com.agentjava.command;

import com.agentjava.memory.MemoryFile;
import com.agentjava.permission.PermissionMode;
import com.agentjava.session.SessionInfo;
import com.agentjava.session.SessionManager;
import com.agentjava.tool.ToolCategory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 命令注册中心：注册、查找、列举、补全，以及全部内置命令的 handler 实现。
 *
 * <p>职责就是「注册 + 分发」两件事。内置命令的定义与实现都写在这个文件里，
 * 打开一个文件就能看清全部命令——注册表被拆散到各处反而会让人看不出「现在到底有哪些命令」。
 *
 * <p><b>为什么要用读写锁</b>：当前所有命令都在启动时注册，看起来并发用不上。
 * Skill 系统会在运行时（`Skill` 装载 / MCP 接入之后）动态注册新命令，
 * 那时注册与查找就会并行发生，锁是必需的。现在就把锁装上，是为了让后面那一步
 * 不需要回头改数据结构。
 *
 * <p><b>锁为什么不包住 handler 执行</b>：查找在锁内完成、执行在锁外完成。
 * 如果握住读锁去跑 handler，那么在 handler 里触发的任何注册动作都会被写锁排队，
 * 甚至自锁死锁。所以 {@link #handlerOf} 只负责把函数取出来，调用方在锁外执行它。
 */
public class CommandRegistry {

    /** 命令定义，按注册顺序保存（列表顺序本身没有语义，输出时才排序）。 */
    private final List<Command> commands = new ArrayList<>();

    /** 名字/别名 → handler。别名与主名指向同一个 handler，查找时一次命中，不用先解别名。 */
    private final Map<String, CommandHandler> handlers = new HashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // ───────────────────────── 注册 ─────────────────────────

    /**
     * 注册一条命令。
     *
     * <p>冲突检测在这里做：名字或别名被别的命令先占了就抛出
     * {@link CommandConflictException}。这是一个「宁可在开发期崩溃」的选择——
     * 别名冲突不会自愈，也不会报错给用户，只会让某条命令静默失效。
     */
    public void register(Command cmd) {
        if (cmd == null) {
            throw new IllegalArgumentException("不能注册 null 命令");
        }
        lock.writeLock().lock();
        try {
            checkConflict(cmd);
            commands.add(cmd);
            if (cmd.hasHandler()) {
                handlers.put(cmd.name(), cmd.handler());
                for (String alias : cmd.aliases()) {
                    handlers.put(alias, cmd.handler());
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 名字重名、别名撞别人主名、别名撞别人别名，三种情况都要拦。 */
    private void checkConflict(Command incoming) {
        for (Command existing : commands) {
            if (existing.name().equals(incoming.name())) {
                throw new CommandConflictException(
                        "命令名冲突: /" + incoming.name() + " 已被 /" + existing.name() + " 占用");
            }
        }
        for (String alias : incoming.aliases()) {
            for (Command existing : commands) {
                if (existing.name().equals(alias)) {
                    throw new CommandConflictException("别名冲突: /" + alias
                            + " 已经是 /" + existing.name() + " 的主名");
                }
                if (existing.aliases().contains(alias)) {
                    throw new CommandConflictException("别名冲突: /" + alias
                            + " 已被 /" + existing.name() + " 占用");
                }
            }
        }
        for (Command existing : commands) {
            if (existing.aliases().contains(incoming.name())) {
                throw new CommandConflictException("命令名冲突: /" + incoming.name()
                        + " 已被 /" + existing.name() + " 登记为别名");
            }
        }
    }

    // ───────────────────────── 查找 ─────────────────────────

    /** 精确查找：主名或别名命中即可。找不到返回 {@link Optional#empty()}。 */
    public Optional<Command> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            for (Command c : commands) {
                if (c.matches(name)) {
                    return Optional.of(c);
                }
            }
            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 取某条命令的执行函数；没有 handler 的 {@link CommandType#LOCAL_UI} 命令返回 empty，
     * 由 UI 层按名字兜底实现。
     */
    public Optional<CommandHandler> handlerOf(String name) {
        lock.readLock().lock();
        try {
            CommandHandler h = name == null ? null : handlers.get(name);
            return Optional.ofNullable(h);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** {@code /help} 用：可见（非隐藏）命令，按命令名字母序排列。 */
    public List<Command> listVisible() {
        lock.readLock().lock();
        try {
            return commands.stream()
                    .filter(c -> !c.hidden())
                    .sorted(Comparator.comparing(Command::name))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ───────────────────────── Tab 补全 ─────────────────────────

    /**
     * 前缀匹配补全候选。
     *
     * <p>三条规定：
     * <ol>
     *   <li>命中的东西只在名字 / 别名之一上匹配就够了，但返回的候选<b>一律用主名</b>——
     *       补全出 {@code /plan} 比补全出 {@code /p} 更能说明这条命令是干什么；</li>
     *   <li>隐藏命令不进候选；</li>
     *   <li>大小写不敏感，且用 {@link Locale#ROOT}：土耳其语 locale 下
     *       {@code "I".toLowerCase()} 会变成无点 ı，拿默认 locale 做大小写折叠会漏匹配。</li>
     * </ol>
     */
    public List<String> search(String prefix) {
        String raw = prefix == null ? "" : prefix.startsWith("/") ? prefix.substring(1) : prefix;
        String lower = raw.toLowerCase(Locale.ROOT);
        lock.readLock().lock();
        try {
            List<String> out = new ArrayList<>();
            for (Command c : commands) {
                if (c.hidden()) {
                    continue;
                }
                boolean hit = c.name().startsWith(lower);
                if (!hit) {
                    for (String alias : c.aliases()) {
                        if (alias.startsWith(lower)) {
                            hit = true;
                            break;
                        }
                    }
                }
                if (hit) {
                    out.add("/" + c.name());
                }
            }
            out.sort(Comparator.naturalOrder());
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ───────────────────────── 内置命令注册 ─────────────────────────

    /** 建一个装好了全部内置命令的注册中心。 */
    public static CommandRegistry createDefault() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerDefaults();
        return registry;
    }

    private void registerDefaults() {
        // 自己有 handler 的：只读 + Toast 类，全部依赖都能从 CommandContext 里拿到
        register(new Command("help", "显示帮助信息", new String[]{"h", "?"},
                CommandType.LOCAL, "/help [命令名]", "", this::handleHelp));
        register(new Command("status", "显示当前状态", new String[]{"s"},
                CommandType.LOCAL, this::handleStatus));
        register(new Command("permission", "查看权限模式与工具处置矩阵", new String[]{"perm"},
                CommandType.LOCAL, this::handlePermission));
        register(new Command("memory", "记忆管理", new String[]{},
                CommandType.LOCAL, "/memory [list|clear]", "", this::handleMemory));
        register(new Command("session", "会话管理", new String[]{},
                CommandType.LOCAL, "/session [list|clean|delete <id>]", "", this::handleSession));
        register(new Command("review", "审查当前代码变更", new String[]{},
                CommandType.PROMPT, "/review [额外关注点]", "", this::handleReview));
        register(new Command("plan", "进入 Plan Mode（只读、只规划）", new String[]{"p"},
                CommandType.LOCAL_UI, "/plan [任务描述]", "", this::handlePlan));
        register(new Command("do", "退出 Plan Mode，恢复正常工作模式", new String[]{},
                CommandType.LOCAL_UI, this::handleDo));

        // handler 为 null 的：它们要动的是 UI 层的状态，或者要拿 LLM 客户端这类宿主资源。
        // 按 local-ui 的约定交给 UI 层按命令名实现（见 AgentModel.runUiCommand）。
        register(new Command("compact", "压缩上下文", new String[]{"c"},
                CommandType.LOCAL_UI, "/compact [保留重点]", "", null));
        register(new Command("clear", "开启新会话（旧会话存档）", new String[]{},
                CommandType.LOCAL_UI, null));
        register(new Command("mode", "切换权限模式", new String[]{},
                CommandType.LOCAL_UI, "/mode default|accept|plan|yolo",
                "用法: /mode default|accept|plan|yolo", null));
        register(new Command("resume", "回到历史会话", new String[]{"r"},
                CommandType.LOCAL_UI, "/resume <会话 ID>",
                "用法: /resume <会话 ID>。历史会话列表见 /session list", null));
        // 逃生舱：退出这件事跟面向 Insider 的命令清单没关系，但 /help exit 也应该查得到
        register(new Command("exit", "退出程序", new String[]{"quit"},
                CommandType.LOCAL_UI, true, "/exit", "", null));
    }

    // ───────────────────────── 命令实现 ─────────────────────────

    private String handleHelp(CommandContext ctx) {
        String arg = ctx.args() == null ? "" : ctx.args().strip();
        if (!arg.isBlank()) {
            String target = arg.startsWith("/") ? arg.substring(1) : arg;
            Optional<Command> cmd = find(target.toLowerCase(Locale.ROOT));
            if (cmd.isEmpty()) {
                return "没有这个命令：" + arg + "。输入 /help 查看可用命令。";
            }
            return describe(cmd.get());
        }
        List<Command> visible = listVisible();
        int width = 0;
        for (Command c : visible) {
            width = Math.max(width, c.displayNames().length());
        }
        StringBuilder sb = new StringBuilder("可用命令：\n\n");
        for (Command c : visible) {
            sb.append("  ")
                    .append(String.format(Locale.ROOT, "%-" + (width + 2) + "s", c.displayNames()))
                    .append(c.description())
                    .append('\n');
        }
        sb.append("\n输入 /help <命令名> 查看详细用法。");
        return sb.toString();
    }

    private static String describe(Command cmd) {
        StringBuilder sb = new StringBuilder();
        sb.append(cmd.displayNames()).append(" — ").append(cmd.description()).append('\n');
        sb.append("  用法：").append(cmd.usage()).append('\n');
        sb.append("  类型：").append(cmd.type().label());
        if (!cmd.argPrompt().isBlank()) {
            sb.append('\n').append("  缺参数时提示：").append(cmd.argPrompt());
        }
        if (cmd.hidden()) {
            sb.append('\n').append("  （隐藏命令，不出现在 /help 列表与 Tab 补全里）");
        }
        return sb.toString();
    }

    private String handleStatus(CommandContext ctx) {
        int used = ctx.ui().getTokenCount();
        int window = ctx.contextWindow().getAsInt();
        int percent = window > 0 ? Math.round(used * 100.0f / window) : 0;
        StringBuilder sb = new StringBuilder();
        sb.append("AgentJava 状态\n");
        sb.append("──────────────\n");
        sb.append("模式：").append(ctx.currentPermissionMode().name().toLowerCase(Locale.ROOT))
                .append('\n');
        sb.append("Token：").append(thousands(used)).append(" / ").append(thousands(window))
                .append("（").append(percent).append("%）\n");
        sb.append("工具：").append(ctx.toolCount().getAsInt()).append(" 个已启用\n");
        sb.append("记忆：user ").append(ctx.userMemoryCount()).append(" 条，project ")
                .append(ctx.projectMemoryCount()).append(" 条\n");
        sb.append("会话：").append(ctx.sessionId()).append('\n');
        sb.append("模型：").append(ctx.model()).append('\n');
        sb.append("工作目录：").append(ctx.workDir()).append('\n');
        sb.append("版本：v").append(ctx.version());
        return sb.toString();
    }

    /** 权限矩阵直接取自 {@link PermissionMode#decide}，命令层不复制一份。 */
    private String handlePermission(CommandContext ctx) {
        PermissionMode mode = ctx.currentPermissionMode();
        StringBuilder sb = new StringBuilder();
        sb.append("当前权限模式：").append(mode.name().toLowerCase(Locale.ROOT)).append('\n');
        sb.append("该模式下各工具分类的默认处置：\n");
        for (ToolCategory category : ToolCategory.values()) {
            sb.append(String.format(Locale.ROOT, "  %-12s%s%n",
                    categoryLabel(category), decisionLabel(mode.decide(category))));
        }
        sb.append("\n切换模式：/mode default|accept|plan|yolo");
        return sb.toString();
    }

    private static String categoryLabel(ToolCategory category) {
        return switch (category) {
            case READ -> "只读工具";
            case WRITE -> "写文件";
            case COMMAND -> "执行命令";
        };
    }

    private static String decisionLabel(PermissionMode.Decision decision) {
        return switch (decision) {
            case ALLOW -> "放行";
            case ASK -> "需确认";
            case DENY -> "拒绝";
        };
    }

    private String handleMemory(CommandContext ctx) {
        String[] parts = splitOnce(ctx.args());
        switch (parts[0]) {
            case "list" -> {
                return memoryList(ctx);
            }
            case "clear" -> {
                return memoryClear(ctx, parts[1]);
            }
            case "" -> {
                return memorySummary(ctx);
            }
            default -> {
                return "未知子命令：" + parts[0] + "\n用法：/memory [list|clear]";
            }
        }
    }

    private String memorySummary(CommandContext ctx) {
        int user = ctx.userMemoryCount();
        int project = ctx.projectMemoryCount();
        if (user + project == 0) {
            return "（还没有任何自动记忆。跟 Agent 聊上几句后，它会把值得记住的信息自己存进来）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("自动记忆：user ").append(user).append(" 条，project ").append(project)
                .append(" 条（共 ").append(user + project).append(" 条）\n");
        List<MemoryFile> all = ctx.allMemories();
        sb.append("前 ").append(Math.min(3, all.size())).append(" 条：\n");
        for (MemoryFile f : all.subList(0, Math.min(3, all.size()))) {
            sb.append("  ").append(memoryLine(f)).append('\n');
        }
        sb.append("\n/memory list 看全部 · /memory clear 清空");
        return sb.toString();
    }

    private String memoryList(CommandContext ctx) {
        List<MemoryFile> user = ctx.userMemories() == null ? List.of() : orEmpty(ctx.userMemories().get());
        List<MemoryFile> project = ctx.projectMemories() == null ? List.of() : orEmpty(ctx.projectMemories().get());
        if (user.isEmpty() && project.isEmpty()) {
            return "（还没有任何自动记忆）";
        }
        StringBuilder sb = new StringBuilder("记忆清单（共 ")
                .append(user.size() + project.size()).append(" 条）\n\n");
        if (!project.isEmpty()) {
            sb.append("  项目记忆·跟项目走：").append(ctx.workDir()).append('\n');
            for (MemoryFile f : project) {
                sb.append("    ").append(memoryLine(f)).append('\n');
            }
        }
        if (!user.isEmpty()) {
            sb.append("  用户记忆·跟人走\n");
            for (MemoryFile f : user) {
                sb.append("    ").append(memoryLine(f)).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    private String memoryClear(CommandContext ctx, String flag) {
        int total = ctx.userMemoryCount() + ctx.projectMemoryCount();
        if (total == 0) {
            return "（没有记忆可以清空）";
        }
        if (!flag.equals("--yes") && !flag.equals("-y")) {
            return "⚠ 即将删除全部 " + total + " 条自动记忆（含两份 MEMORY.md 索引），不可撤销。\n"
                    + "确认执行：/memory clear --yes";
        }
        if (ctx.clearMemories() == null) {
            return "✗ 当前环境没有可用的清空入口";
        }
        ctx.clearMemories().run();
        return "✻ 已清空 " + total + " 条自动记忆";
    }

    private static String memoryLine(MemoryFile f) {
        String type = f.type().isBlank() ? "unknown" : f.type();
        String name = f.name().isBlank() ? f.filename() : f.name();
        return "[" + type + "] " + name
                + (f.description().isBlank() ? "" : " — " + f.description());
    }

    private String handleSession(CommandContext ctx) {
        String[] parts = splitOnce(ctx.args());
        switch (parts[0]) {
            case "" -> {
                return sessionInfo(ctx);
            }
            case "list" -> {
                return sessionList(ctx);
            }
            case "clean" -> {
                int removed = SessionManager.cleanExpiredSessions(ctx.workDir());
                return "✻ 已清理过期会话: " + removed + " 个（阈值 " + SessionManager.EXPIRY_DAYS + " 天）";
            }
            case "delete" -> {
                return sessionDelete(ctx, parts[1]);
            }
            default -> {
                return "未知子命令：" + parts[0] + "\n"
                        + "用法：/session [list|clean|delete <id>]\n"
                        + "切换会话用 /resume <id>，开新会话用 /clear。";
            }
        }
    }

    private String sessionInfo(CommandContext ctx) {
        List<SessionInfo> sessions = ctx.allSessions();
        if (sessions.isEmpty()) {
            return "（还没有历史会话）";
        }
        SessionInfo current = null;
        for (SessionInfo s : sessions) {
            if (s.id().equals(ctx.sessionId())) {
                current = s;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("当前会话：").append(ctx.sessionId()).append('\n');
        if (current != null) {
            sb.append("  消息数：").append(current.messageCount()).append('\n');
            sb.append("  最近活动：").append(formatTime(current.modTime())).append('\n');
            if (!current.gitBranch().isBlank()) {
                sb.append("  分支：").append(current.gitBranch()).append('\n');
            }
        } else {
            sb.append("  （尚未落盘：会话存档在第一条消息发出后才生成）\n");
        }
        sb.append("历史会话：").append(sessions.size()).append(" 个 · /session list 查看 · /resume <id> 切换");
        return sb.toString();
    }

    private String sessionList(CommandContext ctx) {
        List<SessionInfo> sessions = ctx.allSessions();
        if (sessions.isEmpty()) {
            return "（还没有历史会话）";
        }
        StringBuilder sb = new StringBuilder("Sessions (").append(sessions.size()).append(")\n");
        for (SessionInfo s : sessions) {
            String mark = s.id().equals(ctx.sessionId()) ? "* " : "  ";
            sb.append(mark).append(s.id())
                    .append("  ").append(formatTime(s.modTime()))
                    .append(" · ").append(s.messageCount()).append(" 条")
                    .append(" · ").append(formatBytes(s.fileSize()));
            if (!s.gitBranch().isBlank()) {
                sb.append(" · ").append(s.gitBranch());
            }
            sb.append('\n');
            String title = s.firstMessage().isBlank() ? "(no title)" : s.firstMessage();
            sb.append("   ").append(title).append('\n');
        }
        sb.append("\n* 当前会话 · /resume <id> 回到指定会话 · /clear 开新会话");
        return sb.toString();
    }

    private String sessionDelete(CommandContext ctx, String id) {
        if (id.isBlank()) {
            return "用法：/session delete <会话 ID>（列表见 /session list）";
        }
        if (id.equals(ctx.sessionId())) {
            return "✗ 不能删除正在使用的会话 " + id + "。要换个干净上下文请用 /clear。";
        }
        boolean ok = SessionManager.deleteSession(ctx.workDir(), id);
        return ok ? "✻ 已删除会话 " + id : "✗ 找不到会话: " + id;
    }

    /**
     * {@code /review}：唯一的 prompt 类型命令——本文件里唯一会消耗 token 的一个。
     * handler 只负责拼提示词，真正跑起来是 Agent Loop 的事。
     */
    private String handleReview(CommandContext ctx) {
        ctx.ui().addSystemMessage("✻ /review 会触发一次完整的 Agent Loop（消耗 token）");
        StringBuilder prompt = new StringBuilder(
                "请审查当前 git diff 中的代码变更。重点关注：\n"
                        + "1. 逻辑错误\n2. 安全问题\n3. 性能问题\n4. 代码风格");
        String extra = ctx.args() == null ? "" : ctx.args().strip();
        if (!extra.isBlank()) {
            prompt.append("\n\n额外关注：").append(extra);
        }
        return prompt.toString();
    }

    /** {@code /plan 设计用户认证模块}：切进 Plan Mode 的同时把任务描述交给 Agent。 */
    private String handlePlan(CommandContext ctx) {
        ctx.ui().setPlanMode(true);
        String task = ctx.args() == null ? "" : ctx.args().strip();
        if (!task.isBlank()) {
            ctx.ui().sendUserMessage(task);
            return "✻ 已进入 Plan Mode，并把任务描述交给 Agent：\n  " + task;
        }
        return "✻ 已进入 Plan Mode（只读，只规划不改动）。用 /do 退出。";
    }

    private String handleDo(CommandContext ctx) {
        ctx.ui().setPlanMode(false);
        return "✻ 已退出 Plan Mode，恢复正常工作模式。";
    }

    // ───────────────────────── 小工具 ─────────────────────────

    /** 按第一个空白拆成「子命令 + 剩余参数」两段，没输入时返回空串而不是 null。 */
    private static String[] splitOnce(String args) {
        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isEmpty()) {
            return new String[]{"", ""};
        }
        String[] parts = trimmed.split("\\s+", 2);
        return parts.length == 1 ? new String[]{parts[0], ""} : parts;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String thousands(int n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String formatTime(Instant instant) {
        if (instant == null || Instant.EPOCH.equals(instant)) {
            return "-";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault()).format(instant);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return "%.1fKB".formatted(Locale.ROOT, bytes / 1024.0);
        }
        return "%.1fMB".formatted(Locale.ROOT, bytes / (1024.0 * 1024.0));
    }
}
