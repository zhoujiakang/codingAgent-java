package com.agentjava.skill;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Skill 目录：发现、加载、元信息检索与上下文生成。
 *
 * <p>即「两阶段加载」：
 * <ol>
 *   <li><b>阶段一</b>（{@link #loadCatalog}）：启动时只把 frontmatter 读进内存，
 *       产出一份只有 name + description 的清单，随首条 system-reminder 注入对话。
 *       一个 Skill 只占一行，几十个也不过几百 token；</li>
 *   <li><b>阶段二</b>（{@link #getFull}）：模型判断意图匹配后调 LoadSkill 工具，
 *       这时才把完整 SOP 正文读进来。</li>
 * </ol>
 *
 * <p><b>三级搜索路径</b>（优先级从低到高注册，后注册的覆盖先注册的）：
 * <pre>
 *   内置级  BuiltinSkills.load()
 *   用户级  ~/.agentjava/skills/
 *   项目级  {workDir}/.agentjava/skills/
 * </pre>
 * 同名时项目级赢用户级赢内置级——和 npm、gitconfig 的「就近覆盖」是同一个套路。
 * 项目级 Skill 可以提交进版本库，团队共享；用户级的跟人走。
 *
 * <p><b>为什么用 LinkedHashMap</b>：{@code put} 是覆盖语义，同名后来者赢，优先级
 * 天然成立；同时保留插入顺序，清单顺序稳定，模型每次看到的 Skill 列表是同一份，
 * 行为一致性更好。
 */
public class SkillCatalog {

    // ───────────────────────── 数据类型 ─────────────────────────

    /**
     * Skill 元信息，即 SKILL.md 的 YAML frontmatter。
     *
     * <p>{@code name} 既是唯一标识也是斜杠命令名（{@code /<name>}），所以必须
     * 与内置命令不重名——注册斜杠命令时会先查一次注册表，撞了就跳过。
     *
     * <p>{@code mode} 只有 {@code inline} / {@code fork} 两个合法值，默认 inline。
     * {@code forkContext} 控制 fork 模式下子 Agent 继承多少父对话：
     * {@code none}（默认，完全隔离）/ {@code recent}（最近 5 条）/ {@code full}（完整拷贝）。
     */
    public record SkillMeta(String name,
                            String description,
                            String whenToUse,
                            List<String> tags,
                            String mode,
                            String model,
                            String forkContext) {

        /** 是否为 fork 执行模式（声明了隔离意图）。 */
        public boolean isFork() {
            return "fork".equals(mode);
        }
    }

    /**
     * Meta + 可执行体。
     *
     * <p>{@code sourceDir} 记着磁盘路径，热重载时从这里重读；内置 Skill 没有目录，为 null。
     * {@code bodyLoaded} 标记正文是否已加载——因为 record 不可变，改 body 要用
     * {@link #withBody} 返回新实例。
     */
    public record Skill(SkillMeta meta, String promptBody, Path sourceDir, boolean bodyLoaded) {

        public Skill withBody(String newBody) {
            return new Skill(meta, newBody, sourceDir, true);
        }
    }

    // ───────────────────────── 状态 ─────────────────────────

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    /** Skill 名 → 来源标签（builtin / user / project），给 {@code /skill} 分组展示用。 */
    private final Map<String, String> sources = new LinkedHashMap<>();

    /**
     * skill 根目录 → 加载时记录的修改时间。
     * 目录 modtime 变化意味着有新增或删除的 Skill；已有 Skill 的文件编辑由
     * {@link #getFull} 的按需重读兜住，不靠这个。
     */
    private final Map<String, FileTime> dirModTimes = new LinkedHashMap<>();

    private String workDir;

    // ───────────────────────── 对外 API ─────────────────────────

    public String getWorkDir() {
        return workDir;
    }

    /** 直接登记一个 Skill；同名覆盖，来源标签为空串。 */
    public void register(Skill skill) {
        register(skill, "");
    }

    /** 直接登记一个 Skill，并记下它来自哪一级。 */
    public void register(Skill skill, String source) {
        skills.put(skill.meta().name(), skill);
        sources.put(skill.meta().name(), source);
    }

    public Map<String, Skill> getSkills() {
        return Collections.unmodifiableMap(skills);
    }

    public Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * 取「正文已加载」的 Skill——这是热重载的落点。
     *
     * <p>每次调用都从磁盘重读一遍：改了 SKILL.md 不用重启，下次触发立刻生效。
     * 读失败（文件被删、权限变了）时保留上一次缓存的版本兜底，不让已经能跑的
     * Skill 因为一次 IO 抖动就整体失效。
     */
    public Optional<Skill> getFull(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            return Optional.empty();
        }
        if (skill.sourceDir() == null) {
            return Optional.of(skill);
        }
        try {
            Skill reloaded = loadSkill(skill.sourceDir());
            if (reloaded != null) {
                skills.put(name, reloaded);
                return Optional.of(reloaded);
            }
        } catch (IOException ignored) {
            // 读取失败，保留上一次缓存的版本
        }
        return Optional.of(skill);
    }

    /** 全部 Skill 的元信息，按加载顺序。 */
    public List<SkillMeta> list() {
        return skills.values().stream().map(Skill::meta).toList();
    }

    public List<String> names() {
        return List.copyOf(skills.keySet());
    }

    public int size() {
        return skills.size();
    }

    /** Skill 的来源标签：builtin / user / project。 */
    public String source(String name) {
        return sources.getOrDefault(name, "");
    }

    /**
     * 生成注入对话的 Skill 清单：只有名字和一句话描述。
     *
     * <p>这是「渐进式披露」的关键——完整 SOP 不在这里，等模型调 LoadSkill 再取。
     * 描述超过 200 字符截断，避免某个啰嗦的 Skill 把整段清单撑爆。
     * 一个 Skill 都没有时返回空串，调用方据此跳过这一段。
     */
    public String buildSection(String workDir) {
        var metas = list();
        if (metas.isEmpty()) {
            return "";
        }
        var skillsDir = Path.of(workDir, skillDirName(), "skills");
        var sb = new StringBuilder();
        sb.append("## 可用 Skill\n\n");
        sb.append("Skill 安装目录：").append(skillsDir).append('\n');
        sb.append("新建 Skill 时一律放在该目录下的 <skill-name>/SKILL.md。\n\n");
        sb.append("下面只列出 Skill 名称与一句话描述。需要用到某个 Skill 时，调用 LoadSkill 工具并传入 "
                + "{name: \"<skill-name>\"} 加载它的完整 SOP，然后按其中的 Workflow 执行。\n");
        sb.append("用户也可以直接用 /<skill-name> 显式调用。\n\n");
        for (var meta : metas) {
            var desc = meta.description() == null ? "" : meta.description();
            if (desc.length() > 200) {
                desc = desc.substring(0, 200) + "…";
            }
            sb.append("- /").append(meta.name()).append("：").append(desc).append('\n');
        }
        return sb.toString();
    }

    /** 单个 Skill 的详情，给 {@code /skill info <name>} 用。 */
    public String describe(String name) {
        var skill = getFull(name);
        if (skill.isEmpty()) {
            return "";
        }
        var meta = skill.get().meta();
        var sb = new StringBuilder();
        sb.append("/").append(meta.name()).append(" — ").append(meta.description()).append('\n');
        sb.append("  来源：").append(sourceLabel(source(name))).append('\n');
        sb.append("  模式：").append(meta.mode());
        if (meta.isFork()) {
            sb.append("（隔离上下文执行，forkContext=").append(meta.forkContext()).append('）');
        }
        sb.append('\n');
        if (meta.model() != null && !meta.model().isBlank()) {
            sb.append("  指定模型：").append(meta.model()).append('\n');
        }
        if (meta.whenToUse() != null && !meta.whenToUse().isBlank()) {
            sb.append("  适用场景：").append(meta.whenToUse()).append('\n');
        }
        if (!meta.tags().isEmpty()) {
            sb.append("  标签：").append(String.join("、", meta.tags())).append('\n');
        }
        if (skill.get().sourceDir() != null) {
            sb.append("  目录：").append(skill.get().sourceDir()).append('\n');
            sb.append("  附带资源：").append(String.join("、", listResources(skill.get().sourceDir()))).append('\n');
        }
        sb.append("  正文：").append(skill.get().promptBody().length()).append(" 字符");
        return sb.toString();
    }

    // ───────────────────────── 三级加载 ─────────────────────────

    /**
     * 按「内置 → 用户 → 项目」的顺序合并三级目录，同名后者覆盖前者。
     *
     * <p>只做阶段一：只读 frontmatter，正文留空，等 {@link #getFull} 再补。
     */
    public static SkillCatalog loadCatalog(String workDir) {
        SkillCatalog c = new SkillCatalog();
        c.workDir = workDir;

        // 第一级：内置（优先级最低）
        for (var skill : BuiltinSkills.load()) {
            c.register(skill, "builtin");
        }

        // 第二级：用户全局 ~/.agentjava/skills
        String home = System.getProperty("user.home");
        if (home != null) {
            c.loadTier(Path.of(home, skillDirName(), "skills"), "user");
        }

        // 第三级：项目级 {workDir}/.agentjava/skills
        String wd = workDir == null ? System.getProperty("user.dir") : workDir;
        c.loadTier(Path.of(wd, skillDirName(), "skills"), "project");

        c.snapshotDirModTimes();
        return c;
    }

    /** 整体刷新：重建一份新目录再整体覆盖，保证被删掉的 Skill 也真的消失。 */
    public void reload(String workDir) {
        SkillCatalog fresh = loadCatalog(workDir);
        this.skills.clear();
        this.skills.putAll(fresh.skills);
        this.sources.clear();
        this.sources.putAll(fresh.sources);
        this.workDir = fresh.workDir;
        this.dirModTimes.clear();
        this.dirModTimes.putAll(fresh.dirModTimes);
    }

    /**
     * skill 根目录的 modtime 是否变过——变了说明有 Skill 被新增或删除。
     * 已有 Skill 的内容改动不走这里，{@link #getFull} 每次重读已经覆盖了。
     */
    public boolean needsReload() {
        for (var entry : dirModTimes.entrySet()) {
            Path dir = Path.of(entry.getKey());
            FileTime recorded = entry.getValue();
            try {
                FileTime current = Files.getLastModifiedTime(dir);
                if (!current.equals(recorded)) {
                    return true;
                }
            } catch (IOException e) {
                if (recorded != null) {
                    return true;
                }
            }
        }
        for (String dirPath : skillDirPaths(workDir)) {
            if (!dirModTimes.containsKey(dirPath)) {
                try {
                    Files.getLastModifiedTime(Path.of(dirPath));
                    return true;
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    private void snapshotDirModTimes() {
        dirModTimes.clear();
        for (String dirPath : skillDirPaths(workDir)) {
            try {
                dirModTimes.put(dirPath, Files.getLastModifiedTime(Path.of(dirPath)));
            } catch (IOException e) {
                dirModTimes.put(dirPath, null);
            }
        }
    }

    /**
     * 手工指定一个目录加载（测试与 {@code --skill-dir} 这类入口用）。
     * 走的是同一套解析逻辑，只是来源标签记成目录本身。
     */
    public void loadFromDirectory(Path dir) {
        loadTier(dir, dir == null ? "" : dir.toString());
    }

    private void loadTier(Path dir, String source) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        // 两层 catch 各管各的：内层管单个 Skill 解析失败，外层管目录本身不可读。
        // 一个坏 Skill 不该让整批 Skill 都加载不出来。
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(skillDir -> {
                try {
                    Skill skill = loadSkill(skillDir);
                    if (skill != null) {
                        register(skill, source);
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    // ───────────────────────── 解析 ─────────────────────────

    /**
     * 两种定义格式，依次尝试：
     * <ol>
     *   <li>{@code skill.yaml} + {@code prompt.md}：元信息与正文分离，适合正文很长的 Skill；</li>
     *   <li>{@code SKILL.md}：单文件，元信息写 YAML frontmatter，正文写在 {@code ---} 之后。</li>
     * </ol>
     * 都没有就返回 null，调用方静默跳过。
     */
    static Skill loadSkill(Path dir) throws IOException {
        Path metaPath = dir.resolve("skill.yaml");
        if (Files.isRegularFile(metaPath)) {
            return loadFromYamlAndPrompt(dir, metaPath);
        }
        Path mdPath = dir.resolve("SKILL.md");
        if (Files.isRegularFile(mdPath)) {
            return parseSkillMD(dir, Files.readString(mdPath));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Skill loadFromYamlAndPrompt(Path dir, Path metaPath) throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> map = yaml.load(Files.readString(metaPath));
        if (map == null) {
            map = Map.of();
        }
        SkillMeta meta = metaFromMap(map, dir);

        String promptBody = "";
        Path promptPath = dir.resolve("prompt.md");
        if (Files.isRegularFile(promptPath)) {
            promptBody = Files.readString(promptPath);
        }
        return new Skill(meta, promptBody, dir, true);
    }

    /**
     * 解析单文件 SKILL.md。
     *
     * <p>frontmatter 用两次 {@code indexOf} 定位两个 {@code ---}——手写而不是上
     * 通用 YAML 库，是因为只要切出开头那一段，没必要为几行元数据引入完整解析器。
     * YAML 解析失败时整段内容当作正文用：作者的 frontmatter 写错了，SOP 本身
     * 仍然可能是有价值的，丢掉比保留更糟。
     */
    @SuppressWarnings("unchecked")
    static Skill parseSkillMD(Path dir, String content) {
        String body = content == null ? "" : content;
        Map<String, Object> frontMatter = Map.of();

        String trimmed = body.stripLeading();
        if (trimmed.startsWith("---")) {
            int firstSep = body.indexOf("---");
            int secondSep = body.indexOf("---", firstSep + 3);
            if (secondSep >= 0) {
                String yamlBlock = body.substring(firstSep + 3, secondSep);
                body = body.substring(secondSep + 3).strip();
                try {
                    Map<String, Object> parsed = new Yaml().load(yamlBlock);
                    if (parsed != null) {
                        frontMatter = parsed;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        SkillMeta meta = metaFromMap(frontMatter, dir);

        // description 缺失时从正文第一行「非空、非标题」的文字里兜一个出来：
        // 清单里每一行都得有东西可显示，空白描述会让模型无从判断
        String description = meta.description();
        if (description == null || description.isBlank()) {
            String inferred = "";
            for (String line : body.split("\n")) {
                String stripped = line.strip();
                if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                    inferred = stripped;
                    break;
                }
            }
            meta = new SkillMeta(meta.name(), inferred, meta.whenToUse(), meta.tags(),
                    meta.mode(), meta.model(), meta.forkContext());
        }

        return new Skill(meta, body, dir, true);
    }

    @SuppressWarnings("unchecked")
    private static SkillMeta metaFromMap(Map<String, Object> map, Path dir) {
        String name = stringVal(map, "name");
        if (name == null || name.isBlank()) {
            // 没写 name 就用目录名：小写化 + 空格转连字符，保证是个合法的命令名
            name = dir.getFileName().toString().toLowerCase(Locale.ROOT).replace(' ', '-');
        }
        String description = stringVal(map, "description");
        String whenToUse = stringVal(map, "when_to_use");

        List<String> tags = List.of();
        Object rawTags = map.get("tags");
        if (rawTags instanceof List<?> list) {
            tags = list.stream().map(Object::toString).toList();
        }

        String mode = stringVal(map, "mode");
        if (mode == null || mode.isBlank()) {
            // 向后兼容：早期 Skill 用 context: fork 表示 fork 模式
            mode = "fork".equals(stringVal(map, "context")) ? "fork" : "inline";
        }

        String model = stringVal(map, "model");

        String forkContext = stringVal(map, "fork_context");
        if (forkContext == null || forkContext.isBlank()) {
            forkContext = "none";
        }

        return new SkillMeta(
                name,
                description != null ? description : "",
                whenToUse != null ? whenToUse : "",
                tags,
                mode,
                model != null ? model : "",
                forkContext);
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    // ───────────────────────── 目录型 Skill ─────────────────────────

    /**
     * 列出 Skill 目录里除 SKILL.md 之外的附带资源（references/、scripts/、examples/…）。
     *
     * <p>目录型 Skill 的价值就在这些附带文件：SOP 正文只写流程，长篇参考资料和
     * 可执行脚本留在目录里，由模型用现成的 ReadFile / Bash 按需读取，不占上下文。
     * 所以 SKILL.md 里必须写清楚什么时候去读哪一个文件。
     */
    static List<String> listResources(Path sourceDir) {
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.walk(sourceDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> !"SKILL.md".equals(p.getFileName().toString()))
                    .filter(p -> !"skill.yaml".equals(p.getFileName().toString()))
                    .filter(p -> !"prompt.md".equals(p.getFileName().toString()))
                    .map(p -> sourceDir.relativize(p).toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

    /** 本项目沿用 {@code .agentjava}。 */
    static String skillDirName() {
        return ".agentjava";
    }

    static List<String> skillDirPaths(String workDir) {
        List<String> dirs = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null) {
            dirs.add(Path.of(home, skillDirName(), "skills").toString());
        }
        String wd = workDir == null ? System.getProperty("user.dir") : workDir;
        if (wd != null) {
            dirs.add(Path.of(wd, skillDirName(), "skills").toString());
        }
        return dirs;
    }

    public static String sourceLabel(String source) {
        return switch (source == null ? "" : source) {
            case "builtin" -> "内置";
            case "user" -> "用户级";
            case "project" -> "项目级";
            default -> source.isEmpty() ? "未知" : source;
        };
    }
}
