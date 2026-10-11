package com.agentjava.skill;

import com.agentjava.conversation.Message;
import com.agentjava.skill.SkillCatalog.Skill;
import com.agentjava.skill.SkillCatalog.SkillMeta;
import com.agentjava.tool.ToolResult;
import com.agentjava.tool.impl.LoadSkillTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线验证：Skill 定义解析 → 三级加载 → 两阶段加载 → 两种执行模式。
 *
 * <p>全部走真实磁盘（{@link TempDir}），不 mock 文件系统：这里最容易出问题的
 * 地方恰恰是路径与容错（目录不存在、单个 Skill 坏掉），用假 FS 测不出来。
 */
class VerifySkill {

    @TempDir
    Path tempDir;

    private String originalHome;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    // ───────────────────────── 定义解析 ─────────────────────────

    @Test
    @DisplayName("SKILL.md：frontmatter 与正文分离，元信息各字段读准")
    void parseFrontMatterAndBody() {
        String md = """
                ---
                name: commit
                description: 分析 git diff 并生成规范的 commit
                mode: fork
                model: deepseek-reasoner
                fork_context: recent
                when_to_use: 用户说「帮我提交一下」时
                tags: [git, workflow]
                ---

                # Commit SOP

                1. 运行 git status
                2. 运行 git diff --stat
                """;
        Skill skill = SkillCatalog.parseSkillMD(Path.of("/skills/commit"), md);

        assertEquals("commit", skill.meta().name());
        assertEquals("分析 git diff 并生成规范的 commit", skill.meta().description());
        assertEquals("fork", skill.meta().mode());
        assertTrue(skill.meta().isFork());
        assertEquals("deepseek-reasoner", skill.meta().model());
        assertEquals("recent", skill.meta().forkContext());
        assertEquals("用户说「帮我提交一下」时", skill.meta().whenToUse());
        assertEquals(List.of("git", "workflow"), skill.meta().tags());
        // 正文不含 frontmatter，也不含首尾空行
        assertTrue(skill.promptBody().startsWith("# Commit SOP"), skill.promptBody());
        assertFalse(skill.promptBody().contains("name: commit"), skill.promptBody());
        assertTrue(skill.bodyLoaded());
    }

    @Test
    @DisplayName("缺省值：mode 默认 inline、fork_context 默认 none、name 从目录名推断")
    void defaultsAreFilledIn() {
        Skill skill = SkillCatalog.parseSkillMD(Path.of("/skills/My Tool"), "# Title\n\n正文");
        assertEquals("my-tool", skill.meta().name());
        assertEquals("inline", skill.meta().mode());
        assertFalse(skill.meta().isFork());
        assertEquals("none", skill.meta().forkContext());
        assertEquals("", skill.meta().model());
    }

    @Test
    @DisplayName("description 缺失时从正文第一行非空非标题行兜一个出来")
    void descriptionFallsBackToFirstBodyLine() {
        Skill skill = SkillCatalog.parseSkillMD(
                Path.of("/skills/x"), "# 标题\n\n这是真正的一句话说明\n\n后续内容");
        assertEquals("这是真正的一句话说明", skill.meta().description());
    }

    @Test
    @DisplayName("向后兼容：早期用 context: fork 表达的 fork 模式仍然认得")
    void legacyContextForkIsRecognized() {
        Skill skill = SkillCatalog.parseSkillMD(Path.of("/skills/x"),
                "---\nname: old\ncontext: fork\n---\n正文");
        assertEquals("fork", skill.meta().mode());
        // 显式写了 mode 时以 mode 为准
        Skill explicit = SkillCatalog.parseSkillMD(Path.of("/skills/y"),
                "---\nname: y\nmode: inline\ncontext: fork\n---\n正文");
        assertEquals("inline", explicit.meta().mode());
    }

    @Test
    @DisplayName("frontmatter 写坏了：整段内容当正文用，不抛异常")
    void brokenFrontMatterDegradesToPlainBody() {
        Skill skill = SkillCatalog.parseSkillMD(Path.of("/skills/broken"),
                "---\nname: [unclosed\n  bad: : yaml\n---\n\n真正的正文");
        assertTrue(skill.promptBody().contains("真正的正文"), skill.promptBody());
        assertNotNull(skill.meta().name());
    }

    @Test
    @DisplayName("skill.yaml + prompt.md 分离格式：元数据与正文各读各的")
    void yamlAndPromptSplitFormat() throws IOException {
        Path dir = tempDir.resolve("split-demo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("skill.yaml"),
                "name: split\ndescription: 分离格式\nmode: inline\n");
        Files.writeString(dir.resolve("prompt.md"), "分离格式的正文");

        Skill skill = SkillCatalog.loadSkill(dir);
        assertEquals("split", skill.meta().name());
        assertEquals("分离格式", skill.meta().description());
        assertEquals("分离格式的正文", skill.promptBody());
    }

    @Test
    @DisplayName("目录里既没有 SKILL.md 也没有 skill.yaml：返回 null，由调用方跳过")
    void emptyDirectoryYieldsNull() throws IOException {
        Path dir = tempDir.resolve("not-a-skill");
        Files.createDirectories(dir);
        assertNull(SkillCatalog.loadSkill(dir));
    }

    // ───────────────────────── 三级加载 ─────────────────────────

    @Test
    @DisplayName("同名时项目级覆盖用户级，不同名则合并")
    void projectOverridesUser() throws IOException {
        Path home = tempDir.resolve("home");
        Path project = tempDir.resolve("project");
        writeSkill(home, "commit", "commit", "用户级的 commit 描述");
        writeSkill(home, "only-user", "only-user", "只有用户级有");
        writeSkill(project, "commit", "commit", "项目级的 commit 描述");

        SkillCatalog catalog = SkillCatalog.loadCatalog(project.toString());
        assertEquals(2, catalog.size());
        assertEquals("项目级的 commit 描述", catalog.get("commit").orElseThrow().meta().description());
        assertEquals("project", catalog.source("commit"));
        assertEquals("user", catalog.source("only-user"));
    }

    @Test
    @DisplayName("三个目录都不存在：目录为空，不抛异常")
    void missingDirectoriesAreIgnored() {
        SkillCatalog catalog = SkillCatalog.loadCatalog(tempDir.resolve("nope").toString());
        assertEquals(0, catalog.size());
        assertEquals("", catalog.buildSection("x"));
    }

    @Test
    @DisplayName("单个 Skill 解析失败不影响同批其他 Skill")
    void brokenSkillDoesNotBlockOthers() throws IOException {
        Path home = tempDir.resolve("home");
        writeSkill(home, "good", "good", "好的");
        Files.createDirectories(home.resolve(".agentjava").resolve("skills").resolve("bad"));
        // bad 目录里放一个不可读的东西（没有 SKILL.md / skill.yaml），等价于空目录
        SkillCatalog catalog = SkillCatalog.loadCatalog(tempDir.resolve("project").toString());
        assertEquals(1, catalog.size());
        assertTrue(catalog.get("good").isPresent());
    }

    // ───────────────────────── 两阶段加载与热重载 ─────────────────────────

    @Test
    @DisplayName("阶段一（loadCatalog）只有元信息，正文由 getFull 补读")
    void twoPhaseLoading() throws IOException {
        Path project = tempDir.resolve("project");
        writeSkill(project, "demo", "demo", "演示");

        SkillCatalog catalog = SkillCatalog.loadCatalog(project.toString());
        Skill first = catalog.get("demo").orElseThrow();
        assertTrue(first.bodyLoaded(), "SKILL.md 单文件格式在阶段一就把正文读进来了");

        String full = catalog.getFull("demo").orElseThrow().promptBody();
        assertEquals(first.promptBody(), full);
    }

    @Test
    @DisplayName("热重载：改了 SKILL.md 下次 getFull 就是新内容，不用重启")
    void hotReloadPicksUpEdits() throws IOException {
        Path project = tempDir.resolve("project");
        Path file = writeSkill(project, "demo", "demo", "第一版");
        SkillCatalog catalog = SkillCatalog.loadCatalog(project.toString());
        assertEquals("SOP 正文：demo", catalog.getFull("demo").orElseThrow().promptBody().strip());
        assertEquals("第一版", catalog.get("demo").orElseThrow().meta().description());

        Files.writeString(file, "---\nname: demo\ndescription: 第二版\n---\n\n第二版正文");
        assertTrue(catalog.getFull("demo").orElseThrow().promptBody().contains("第二版正文"));
        assertEquals("第二版", catalog.getFull("demo").orElseThrow().meta().description());
    }

    @Test
    @DisplayName("热重载读失败时保留上一次缓存的版本，不让 Skill 整体失效")
    void hotReloadFailureKeepsCache() throws IOException {
        Path project = tempDir.resolve("project");
        Path file = writeSkill(project, "demo", "demo", "缓存版");
        SkillCatalog catalog = SkillCatalog.loadCatalog(project.toString());
        Files.delete(file);
        // SKILL.md 没了，loadSkill 返回 null → 保留上一次缓存的正文
        assertEquals("SOP 正文：demo", catalog.getFull("demo").orElseThrow().promptBody().strip());
        assertTrue(catalog.getFull("demo").isPresent());
    }

    @Test
    @DisplayName("needsReload 只在 Skill 增删时为真；reload 之后重新归位")
    void needsReloadTracksDirectoryChanges() throws IOException {
        Path project = tempDir.resolve("project");
        writeSkill(project, "demo", "demo", "演示");
        SkillCatalog catalog = SkillCatalog.loadCatalog(project.toString());
        assertFalse(catalog.needsReload());

        writeSkill(project, "another", "another", "另一个");
        assertTrue(catalog.needsReload());

        catalog.reload(project.toString());
        assertFalse(catalog.needsReload());
        assertEquals(2, catalog.size());
    }

    @Test
    @DisplayName("reload 会真的移除被删掉的 Skill，而不是只增不减")
    void reloadDropsDeletedSkills() throws IOException {
        Path project = tempDir.resolve("project");
        writeSkill(project, "a", "a", "A");
        writeSkill(project, "b", "b", "B");
        SkillCatalog catalog = SkillCatalog.loadCatalog(project.toString());
        assertEquals(2, catalog.size());

        deleteSkill(project, "b");
        catalog.reload(project.toString());
        assertEquals(1, catalog.size());
        assertTrue(catalog.get("b").isEmpty());
    }

    // ───────────────────────── 注入上下文 ─────────────────────────

    @Test
    @DisplayName("buildSection 只列名字与一句话描述，并指明要调 LoadSkill")
    void sectionIsLightweight() throws IOException {
        Path project = tempDir.resolve("project");
        writeSkill(project, "commit", "commit", "分析 git diff 并生成规范的 commit");
        writeSkill(project, "review", "review", "审查代码变更");

        String section = SkillCatalog.loadCatalog(project.toString())
                .buildSection(project.toString());
        assertTrue(section.contains("## 可用 Skill"), section);
        assertTrue(section.contains("- /commit：分析 git diff 并生成规范的 commit"), section);
        assertTrue(section.contains("- /review：审查代码变更"), section);
        assertTrue(section.contains("LoadSkill"), section);
        // 清单里不能出现 SOP 正文
        assertFalse(section.contains("SOP 正文"), section);
        // 每个 Skill 只占一行
        assertEquals(2, section.lines().filter(l -> l.startsWith("- /")).count());
    }

    @Test
    @DisplayName("超长描述截断到 200 字符，避免一个啰嗦 Skill 撑爆清单")
    void longDescriptionIsTruncated() throws IOException {
        Path project = tempDir.resolve("project");
        writeSkill(project, "verbose", "verbose", "描".repeat(500));
        String section = SkillCatalog.loadCatalog(project.toString())
                .buildSection(project.toString());
        String line = section.lines().filter(l -> l.startsWith("- /verbose")).findFirst().orElseThrow();
        assertTrue(line.endsWith("…"), line);
        assertEquals(201, line.length() - "- /verbose：".length(), "截断后应为 200 字 + 一个省略号");
    }

    @Test
    @DisplayName("describe 给出模式、来源、目录与附带资源")
    void describeShowsDetails() throws IOException {
        Path project = tempDir.resolve("project");
        Path dir = project.resolve(".agentjava").resolve("skills").resolve("pack");
        Files.createDirectories(dir.resolve("references"));
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: pack\ndescription: 目录型 Skill\nmode: fork\nfork_context: full\n---\n\n正文");
        Files.writeString(dir.resolve("references").resolve("guide.md"), "长篇参考资料");
        Files.writeString(dir.resolve("run.sh"), "#!/bin/sh\necho hi");

        SkillCatalog catalog = SkillCatalog.loadCatalog(project.toString());
        String info = catalog.describe("pack");
        assertTrue(info.contains("/pack — 目录型 Skill"), info);
        assertTrue(info.contains("模式：fork"), info);
        assertTrue(info.contains("forkContext=full"), info);
        assertTrue(info.contains("来源：项目级"), info);
        assertTrue(info.contains("references/guide.md"), info);
        assertTrue(info.contains("run.sh"), info);
        assertTrue(info.contains("正文：2 字符"), info);
        assertEquals("", catalog.describe("不存在"));
    }

    @Test
    @DisplayName("目录型 Skill：附带资源被列出来，SKILL.md 本身不算资源")
    void directorySkillListsResources() throws IOException {
        Path dir = tempDir.resolve("pack");
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("SKILL.md"), "# 正文");
        Files.writeString(dir.resolve("scripts").resolve("a.sh"), "x");
        Files.writeString(dir.resolve("tool.json"), "{}");

        List<String> resources = SkillCatalog.listResources(dir);
        assertTrue(resources.contains("scripts/a.sh"), resources.toString());
        assertTrue(resources.contains("tool.json"), resources.toString());
        assertFalse(resources.contains("SKILL.md"), resources.toString());
        assertEquals(List.of("scripts/a.sh", "tool.json"), resources);
    }

    // ───────────────────────── 参数替换 ─────────────────────────

    @Test
    @DisplayName("$ARGUMENTS 替换 / 没占位符时追加 / 无参数时原样返回")
    void argumentSubstitution() {
        String withPlaceholder = "对 $ARGUMENTS 做审查";
        assertEquals("对 src/Main.java 做审查",
                SkillExecutor.substituteArguments(withPlaceholder, "src/Main.java"));
        // 空参数不触发替换
        assertEquals(withPlaceholder, SkillExecutor.substituteArguments(withPlaceholder, "   "));
        // 没有占位符就追加到末尾，参数不丢
        String plain = "## Workflow\n\n1. 跑测试";
        assertEquals(plain + "\n\n## User Request\n\n只跑单测",
                SkillExecutor.substituteArguments(plain, "只跑单测"));
    }

    // ───────────────────────── 两种执行模式 ─────────────────────────

    @Test
    @DisplayName("inline：正文进主对话，同时登记激活与调用记录")
    void inlineModeActivatesOnHost() {
        Skill skill = skill("commit", "inline", "SOP $ARGUMENTS");
        RecordingHost host = new RecordingHost();

        String out = SkillExecutor.executeInline(skill, "参数", host);
        assertEquals("SOP 参数", out);
        assertEquals(List.of("commit"), host.activated);
        assertEquals(List.of("commit"), host.recorded);
        assertTrue(host.bodies.get(0).contains("SOP 参数"), "登记的是渲染后的正文");
    }

    @Test
    @DisplayName("fork：正文交给子 Agent，登记的是未替换参数的原文")
    void forkModeRunsSubAgent() {
        Skill skill = skill("review", "fork", "审查 $ARGUMENTS");
        FakeForkHost host = new FakeForkHost();

        String out = SkillExecutor.executeFork(skill, "并发安全", host);
        assertEquals("子 Agent 的结论", out);
        assertEquals("审查 并发安全", host.receivedBody, "子 Agent 拿到的是渲染版");
        assertEquals("审查 $ARGUMENTS", host.recordedBody, "恢复记录里存原始 SOP");
        assertEquals("review", host.recordedName);
        // forkContext 默认 none → 完全隔离，不带任何父对话
        assertEquals(0, host.receivedSeed.size());
    }

    @Test
    @DisplayName("forkContext：none 全隔离 / recent 最近 5 条 / full 全量")
    void forkSeedStrategies() {
        List<Message> parent = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            parent.add(new Message(Message.Role.USER, "u" + i, Message.Status.COMPLETE));
        }
        assertEquals(0, SkillExecutor.buildForkSeed("none", parent).size());
        assertEquals(0, SkillExecutor.buildForkSeed(null, parent).size());
        assertEquals(8, SkillExecutor.buildForkSeed("full", parent).size());
        assertEquals(5, SkillExecutor.buildForkSeed("recent", parent).size());
        assertEquals("u7", SkillExecutor.buildForkSeed("recent", parent).get(4).getContent());
        // 父对话不足 5 条时全带
        assertEquals(3, SkillExecutor.buildForkSeed("recent", parent.subList(0, 3)).size());
        assertEquals(0, SkillExecutor.buildForkSeed("recent", null).size());
    }

    // ───────────────────────── LoadSkill 工具 ─────────────────────────

    @Test
    @DisplayName("LoadSkill：四类错误各有明确文案，模型看到能自己调整")
    void loadSkillErrorPaths() throws IOException {
        Path project = tempDir.resolve("project");
        writeSkill(project, "commit", "commit", "生成 commit");
        SkillCatalog catalog = SkillCatalog.loadCatalog(project.toString());

        var tool = new LoadSkillTool();
        assertTrue(tool.execute(Map.of()).isError());
        assertTrue(tool.execute(Map.of()).output().contains("name is required"));

        tool.setCatalog(catalog);
        assertTrue(tool.execute(Map.of("name", "")).isError());
        ToolResult unknown = tool.execute(Map.of("name", "nope"));
        assertTrue(unknown.isError());
        assertTrue(unknown.output().contains("unknown skill: nope"), unknown.output());

        var empty = new LoadSkillTool();
        assertTrue(empty.execute(Map.of("name", "commit")).output().contains("catalog is null"));
    }

    @Test
    @DisplayName("LoadSkill：inline 模式把完整 SOP 作为工具结果带回，并钉到宿主")
    void loadSkillInlineReturnsSop() throws IOException {
        Path project = tempDir.resolve("project");
        writeSkill(project, "commit", "commit", "生成 commit");
        SkillCatalog catalog = SkillCatalog.loadCatalog(project.toString());

        RecordingHost host = new RecordingHost();
        var tool = new LoadSkillTool();
        tool.setCatalog(catalog);
        tool.setHost(host);

        ToolResult result = tool.execute(Map.of("name", "commit"));
        assertFalse(result.isError());
        assertTrue(result.output().startsWith("# Skill: commit"), result.output());
        assertTrue(result.output().contains("SOP 正文：commit"), result.output());
        assertEquals(List.of("commit"), host.activated);
    }

    @Test
    @DisplayName("LoadSkill：fork 模式走子 Agent，主对话只拿到结论")
    void loadSkillForkDelegatesToSubAgent() throws IOException {
        Path project = tempDir.resolve("project");
        Path dir = project.resolve(".agentjava").resolve("skills").resolve("review");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: review\ndescription: 审查\nmode: fork\n---\n\nSOP 正文：review");
        SkillCatalog catalog = SkillCatalog.loadCatalog(project.toString());

        FakeForkHost host = new FakeForkHost();
        var tool = new LoadSkillTool();
        tool.setCatalog(catalog);
        tool.setForkHost(host);

        ToolResult result = tool.execute(Map.of("name", "review"));
        assertFalse(result.isError());
        assertEquals("子 Agent 的结论", result.output());
        assertFalse(result.output().contains("SOP 正文"), "fork 模式下 SOP 不进主对话");
        assertEquals("SOP 正文：review", host.receivedBody);
    }

    @Test
    @DisplayName("LoadSkill：宿主没接子 Agent 运行时，fork 声明降级成 inline 而不是报错")
    void loadSkillWithoutForkHostDegrades() throws IOException {
        Path project = tempDir.resolve("project");
        Path dir = project.resolve(".agentjava").resolve("skills").resolve("review");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: review\ndescription: 审查\nmode: fork\n---\n\nSOP 正文：review");
        SkillCatalog catalog = SkillCatalog.loadCatalog(project.toString());

        var tool = new LoadSkillTool();
        tool.setCatalog(catalog);
        ToolResult result = tool.execute(Map.of("name", "review"));
        assertFalse(result.isError());
        assertTrue(result.output().contains("SOP 正文：review"), result.output());
    }

    @Test
    @DisplayName("LoadSkill 是只读工具，跟 ReadFile 走同一条通道（Plan Mode 下也放行）")
    void loadSkillIsReadOnly() {
        var tool = new LoadSkillTool();
        assertEquals("LoadSkill", tool.name());
        assertEquals(com.agentjava.tool.ToolCategory.READ, tool.category());
        assertTrue(tool.isConcurrencySafe(Map.of("name", "x")));
        @SuppressWarnings("unchecked")
        var schema = (Map<String, Object>) tool.schema().get("input_schema");
        assertEquals(List.of("name"), schema.get("required"));
    }

    // ───────────────────────── 辅助 ─────────────────────────

    /** 写一个最小可用的 SKILL.md，返回文件路径。 */
    private static Path writeSkill(Path root, String name, String metaName, String description)
            throws IOException {
        Path dir = root.resolve(".agentjava").resolve("skills").resolve(name);
        Files.createDirectories(dir);
        Path file = dir.resolve("SKILL.md");
        Files.writeString(file, "---\nname: " + metaName + "\ndescription: " + description
                + "\n---\n\nSOP 正文：" + metaName + "\n");
        return file;
    }

    private static void deleteSkill(Path root, String name) throws IOException {
        Path dir = root.resolve(".agentjava").resolve("skills").resolve(name);
        Files.deleteIfExists(dir.resolve("SKILL.md"));
        Files.deleteIfExists(dir);
    }

    private static Skill skill(String name, String mode, String body) {
        var meta = new SkillMeta(name, name + " 的描述", "", List.of(), mode, "", "none");
        return new Skill(meta, body, null, true);
    }

    /** 记录调用的 inline 宿主。 */
    private static final class RecordingHost implements SkillHost {

        final List<String> activated = new ArrayList<>();
        final List<String> recorded = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();

        @Override
        public void activateSkill(String name, String body) {
            activated.add(name);
            bodies.add(body);
        }

        @Override
        public void recordSkillInvocation(String name, String body) {
            recorded.add(name);
            bodies.add(body);
        }
    }

    /** 返回固定结论的 fork 宿主。 */
    private static final class FakeForkHost implements com.agentjava.skill.SkillForkHost {

        String receivedBody;
        List<Message> receivedSeed;
        String recordedName;
        String recordedBody;

        @Override
        public void activateSkill(String name, String body) {
        }

        @Override
        public void recordSkillInvocation(String name, String body) {
            recordedName = name;
            recordedBody = body;
        }

        @Override
        public String runSubAgent(String body, List<Message> seed, String model) {
            receivedBody = body;
            receivedSeed = seed;
            return "子 Agent 的结论";
        }

        @Override
        public List<Message> snapshotParentMessages() {
            List<Message> parent = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                parent.add(new Message(Message.Role.USER, "u" + i, Message.Status.COMPLETE));
            }
            return parent;
        }
    }
}
