package com.agentjava.memory;

import com.agentjava.conversation.ConversationManager;
import com.agentjava.conversation.Message;
import com.agentjava.conversation.ToolResultBlock;
import com.agentjava.conversation.ToolUseBlock;
import com.agentjava.history.HistoryStore;
import com.agentjava.session.KeepMessage;
import com.agentjava.session.SessionInfo;
import com.agentjava.session.SessionManager;
import com.agentjava.session.SessionMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 离线验证：跨会话记忆与会话持久化。
 *
 * <p>全部用例离线可跑，不依赖任何 LLM 与网络。文件系统操作一律落在临时目录：
 * 通过临时改写 {@code user.home} 把用户级记忆/历史目录重定向进去，
 * 避免测试在开发者真实的 {@code ~/.agentjava/} 下留下垃圾。
 */
class VerifySessionMemory {

    private Path root;
    private Path home;
    private Path project;
    private Path projectX;
    private String savedHome;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("agentjava-ch9");
        home = Files.createTempDirectory(root, "home");
        project = Files.createTempDirectory(root, "project");
        // 另一个独立临时目录，用于验证 @ 引用的越界拦截
        projectX = Files.createTempDirectory(root, "outside");
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        System.setProperty("user.home", savedHome);
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    // ───────────────────────── 会话持久化 ─────────────────────────

    @Test
    void sessionRoundTripKeepsToolBlocks() {
        String sid = SessionManager.newId();
        var conv = new ConversationManager();
        conv.addUserMessage("读一下 a.txt");
        conv.addAssistantMessageWithTools("好", List.of(
                new ToolUseBlock("tu-1", "ReadFile", Map.of("file_path", "a.txt"))));
        conv.addToolResultsMessage(List.of(new ToolResultBlock("tu-1", "hello world", false)));
        for (Message m : conv.getMessages()) {
            SessionManager.saveConversationMessage(project.toString(), sid, m);
        }

        List<SessionMessage> loaded = SessionManager.loadSession(project.toString(), sid);
        assertEquals(3, loaded.size(), "三条记录都应完整读回");

        assertEquals("user", loaded.get(0).role());
        assertEquals("读一下 a.txt", loaded.get(0).content());

        assertEquals("assistant", loaded.get(1).role());
        assertEquals(1, loaded.get(1).toolUses().size());
        assertEquals("tu-1", loaded.get(1).toolUses().get(0).toolUseId());
        assertEquals("ReadFile", loaded.get(1).toolUses().get(0).toolName());
        assertEquals("a.txt", loaded.get(1).toolUses().get(0).arguments().get("file_path"));

        // 工具结果挂在 user 记录上、正文为空——这是关键的落盘形态
        assertEquals("user", loaded.get(2).role());
        assertEquals("", loaded.get(2).content());
        assertEquals(1, loaded.get(2).toolResults().size());
        assertEquals("hello world", loaded.get(2).toolResults().get(0).content());
        assertFalse(loaded.get(2).toolResults().get(0).isError());
    }

    @Test
    void jsonomitsEmptyFieldsAndKeepsFixedKeyOrder() throws IOException {
        String sid = "20261011-120000-abcd";
        SessionManager.saveConversationMessage(project.toString(), sid,
                new Message(Message.Role.USER, "纯文本", Message.Status.COMPLETE));
        Path file = SessionManager.sessionsDir(project.toString()).resolve(sid + ".jsonl");
        String line = Files.readString(file, StandardCharsets.UTF_8).strip();
        assertFalse(line.contains("tool_uses"), "纯文本消息不该写出空的工具块键");
        assertFalse(line.contains("is_error"), "is_error 为 false 时不该出现");
        assertTrue(line.indexOf("\"role\"") < line.indexOf("\"content\""),
                "字段顺序应固定为 role/content/ts");
    }

    @Test
    void loadSessionSkipsBadLinesButKeepsNeighbours() throws IOException {
        String sid = "20261011-120001-abcd";
        SessionManager.saveConversationMessage(project.toString(), sid,
                new Message(Message.Role.USER, "第一条", Message.Status.COMPLETE));
        Path file = SessionManager.sessionsDir(project.toString()).resolve(sid + ".jsonl");
        Files.writeString(file, "这不是 JSON，彻底坏了\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        SessionManager.saveConversationMessage(project.toString(), sid,
                new Message(Message.Role.USER, "第三条", Message.Status.COMPLETE));

        List<SessionMessage> loaded = SessionManager.loadSession(project.toString(), sid);
        assertEquals(2, loaded.size(), "坏行应被跳过，前后两条都要留下");
        assertEquals("第一条", loaded.get(0).content());
        assertEquals("第三条", loaded.get(1).content());
    }

    @Test
    void toolResultOnlyRecordSurvivesEmptyCheck() {
        // 只带工具结果的记录正文为空；若判空只看 content，整条工具往返会被静默丢掉
        String sid = "20261011-120002-abcd";
        var conv = new ConversationManager();
        conv.addToolResultsMessage(List.of(new ToolResultBlock("tu-9", "结果", false)));
        for (Message m : conv.getMessages()) {
            SessionManager.saveConversationMessage(project.toString(), sid, m);
        }
        List<SessionMessage> loaded = SessionManager.loadSession(project.toString(), sid);
        assertEquals(1, loaded.size(), "只有工具结果、没有正文的记录必须保留");
        assertEquals("tu-9", loaded.get(0).toolResults().get(0).toolUseId());
    }

    @Test
    void sessionIdFollowsTimestampPlusRandomSuffix() {
        String id = SessionManager.newId();
        assertTrue(id.matches("\\d{8}-\\d{6}-[0-9a-f]{4}"),
                "会话 ID 应为 YYYYMMDD-HHMMSS-xxxx，实际为 " + id);
        assertNotEquals(id, SessionManager.newId(), "随机后缀应让同一秒内多次生成不撞名");
    }

    @Test
    void listSessionsSortedByRecentActivityAndCleanupExpired() throws IOException {
        String older = SessionManager.newId();
        String newer = SessionManager.newId();
        write(SessionManager.sessionsDir(project.toString()).resolve(older + ".jsonl"),
                "{\"role\":\"user\",\"content\":\"旧会话\",\"ts\":1}\n");
        write(SessionManager.sessionsDir(project.toString()).resolve(newer + ".jsonl"),
                "{\"role\":\"user\",\"content\":\"新会话\",\"ts\":2}\n");
        // 把 older 的 mtime 推到 31 天前
        Files.setLastModifiedTime(SessionManager.sessionsDir(project.toString())
                        .resolve(older + ".jsonl"),
                FileTime.fromMillis(System.currentTimeMillis() - 31L * 86_400_000L));

        List<SessionInfo> sessions = SessionManager.listSessions(project.toString());
        assertEquals(2, sessions.size());
        assertEquals(newer, sessions.get(0).id(), "排序应按 mtime 倒序，最近的排在最前");
        assertEquals("旧会话", sessions.get(sessions.size() - 1).firstMessage(),
                "listSessions 应从 JSONL 首条用户正文提取标题");

        assertEquals(1, SessionManager.cleanExpiredSessions(project.toString()),
                "超过 30 天的会话应被清理");
        assertEquals(1, SessionManager.listSessions(project.toString()).size());
    }

    // ───────────────────────── 工具调用链修复 ─────────────────────────

    @Test
    void danglingToolUseGetsAnErrorResult() {
        var conv = new ConversationManager();
        conv.addAssistantMessageWithTools("读文件", List.of(
                new ToolUseBlock("tu-1", "ReadFile", Map.of("file_path", "a.txt"))));
        // 故意不补 tool_result，模拟进程在工具返回前被杀

        int fixed = conv.fixToolCallChain();

        assertEquals(1, fixed);
        assertEquals(2, conv.size(), "应补上一条 user 消息承接错误结果");
        var last = conv.getMessages().get(1);
        assertEquals(Message.Role.USER, last.getRole());
        assertEquals(1, last.getToolResults().size());
        assertEquals("tu-1", last.getToolResults().get(0).toolUseId());
        assertTrue(last.getToolResults().get(0).isError(), "补位结果必须是错误，不能假装成功");
    }

    @Test
    void orphanToolResultIsDropped() {
        var conv = new ConversationManager();
        conv.addUserMessage("问题");
        conv.addToolResultsMessage(List.of(new ToolResultBlock("ghost", "无主的回音", false)));

        int fixed = conv.fixToolCallChain();

        assertEquals(1, fixed);
        var last = conv.getMessages().get(conv.size() - 1);
        assertTrue(last.getToolResults() == null || last.getToolResults().isEmpty(),
                "没有对应 tool_use 的结果块应被丢弃");
    }

    @Test
    void matchedRoundTripNeedsNoFix() {
        var conv = new ConversationManager();
        conv.addAssistantMessageWithTools("", List.of(
                new ToolUseBlock("tu-1", "ReadFile", Map.of())));
        conv.addToolResultsMessage(List.of(new ToolResultBlock("tu-1", "内容", false)));
        assertEquals(0, conv.fixToolCallChain(), "已配对的链不该被改动");
    }

    // ───────────────────────── 压缩感知重建 ─────────────────────────

    @Test
    void rebuildUsesLastCompactBoundarySummaryPlusKeepPlusAfter() {
        String sid = SessionManager.newId();
        String workDir = project.toString();
        SessionManager.saveConversationMessage(workDir, sid,
                new Message(Message.Role.USER, "很久以前的问题", Message.Status.COMPLETE));
        SessionManager.saveConversationMessage(workDir, sid,
                new Message(Message.Role.ASSISTANT, "很久以前的回答", Message.Status.COMPLETE));
        SessionManager.saveCompactBoundary(workDir, sid, "这段是对话摘要",
                List.of(new KeepMessage("user", "保留的最近提问", List.of(), List.of()),
                        new KeepMessage("assistant", "保留的最近回答", List.of(), List.of())));
        SessionManager.saveConversationMessage(workDir, sid,
                new Message(Message.Role.USER, "边界之后的新问题", Message.Status.COMPLETE));

        var rebuilt = SessionManager.rebuildConversation(
                SessionManager.loadSession(workDir, sid));

        assertEquals(4, rebuilt.size(), "应为 摘要(1) + 保留尾部(2) + 边界后(1)");
        assertEquals("这段是对话摘要", rebuilt.getMessages().get(0).getContent());
        assertEquals("保留的最近提问", rebuilt.getMessages().get(1).getContent());
        assertEquals("保留的最近回答", rebuilt.getMessages().get(2).getContent());
        assertEquals("边界之后的新问题", rebuilt.getMessages().get(3).getContent());
        assertTrue(rebuilt.getMessages().stream()
                .noneMatch(m -> "很久以前的问题".equals(m.getContent())),
                "边界之前的旧记录应被摘要取代，不再回放");
    }

    @Test
    void replayWithoutBoundaryRestoresEverything() {
        String sid = SessionManager.newId();
        SessionManager.saveConversationMessage(project.toString(), sid,
                new Message(Message.Role.USER, "q", Message.Status.COMPLETE));
        SessionManager.saveConversationMessage(project.toString(), sid,
                new Message(Message.Role.ASSISTANT, "a", Message.Status.COMPLETE));
        var replayed = SessionManager.replay(
                SessionManager.loadSession(project.toString(), sid));
        assertEquals(2, replayed.size());
        assertEquals("q", replayed.getMessages().get(0).getContent());
        assertEquals("a", replayed.getMessages().get(1).getContent());
    }

    // ───────────────────────── 项目指令加载 ─────────────────────────

    @Test
    void instructionsAreConcatenatedFromLowToHighPriority() throws IOException {
        write(home.resolve(".agentjava").resolve("AGENTJAVA.md"), "USER_LEVEL");
        write(project.resolve("AGENTJAVA.md"), "PROJECT_LEVEL");
        write(project.resolve(".agentjava").resolve("AGENTJAVA.md"), "DOT_LEVEL");
        write(project.resolve("AGENTJAVA.local.md"), "LOCAL_LEVEL");

        String result = InstructionLoader.loadInstructions(project.toString());

        assertTrue(result.contains("USER_LEVEL")
                && result.contains("PROJECT_LEVEL")
                && result.contains("DOT_LEVEL")
                && result.contains("LOCAL_LEVEL"), "四个来源都要拼接，不是找到一个就返回");
        assertTrue(result.indexOf("USER_LEVEL") < result.indexOf("PROJECT_LEVEL"),
                "用户全局优先级最低，应排在前面");
        assertTrue(result.indexOf("PROJECT_LEVEL") < result.indexOf("DOT_LEVEL"));
        assertTrue(result.indexOf("DOT_LEVEL") < result.indexOf("LOCAL_LEVEL"),
                "本地私有优先级最高，应排在最后");
        assertTrue(result.contains("---"), "来源之间用分隔线连接");
    }

    @Test
    void includeExpandsRelativePathButNotInsideCodeFence() throws IOException {
        write(project.resolve("AGENTJAVA.md"),
                "主文件\n@./sub.md\n```java\n@./should-not-expand.md\n```\n");
        write(project.resolve("sub.md"), "SUB_CONTENT");
        write(project.resolve("should-not-expand.md"), "FENCED_TARGET");

        String result = InstructionLoader.loadInstructions(project.toString());

        assertTrue(result.contains("SUB_CONTENT"), "正文里的 @./sub.md 应被展开");
        assertFalse(result.contains("FENCED_TARGET"),
                "代码块里的 @ 引用必须原样保留——文档里写 @Override 这类注解很常见");
        assertTrue(result.contains("@./should-not-expand.md"), "未展开的引用行应保留原文");
    }

    @Test
    void includeCycleTerminatesAndDepthIsCapped() throws IOException {
        // 循环引用：a → b → a
        write(project.resolve("AGENTJAVA.md"), "根\n@./b.md\n");
        write(project.resolve("b.md"), "B\n@./a.md\n");
        write(project.resolve("a.md"), "A\n@./b.md\n");

        String result = InstructionLoader.loadInstructions(project.toString());
        assertTrue(result.contains("A") && result.contains("B"), "环里的文件都应至少加载一次");
        // 若没有 visited 集合会无限递归 -> 这里能正常返回即已通过

        // 深度上限：链式引用超过 5 层后停止展开
        Path deep = Files.createTempDirectory(root, "deep");
        for (int i = 0; i < 10; i++) {
            write(deep.resolve("f" + i + ".md"),
                    "MARK" + i + "\n@./f" + (i + 1) + ".md\n");
        }
        String deepResult = InstructionLoader.expandIncludes(
                Files.readString(deep.resolve("f0.md"), StandardCharsets.UTF_8),
                deep.toString(), new java.util.HashSet<>(), 0, deep.toString());
        assertTrue(deepResult.contains("MARK6"), "应当往下展开若干层");
        assertFalse(deepResult.contains("MARK7"),
                "层级达到 MAX_INCLUDE_DEPTH 后停止继续展开");
    }

    @Test
    void includeOutsideProjectIsReplacedWithHtmlComment() throws IOException {
        Path outsideFile = projectX.resolve("secret.md");
        write(outsideFile, "OUTSIDE_SECRET");
        write(project.resolve("AGENTJAVA.md"), "主\n@" + outsideFile + "\n");

        String result = InstructionLoader.loadInstructions(project.toString());

        assertFalse(result.contains("OUTSIDE_SECRET"), "越界的引用不能被静默拉进上下文");
        assertTrue(result.contains("Failed to include"),
                "越界引用应留下可见的占位注释，让模型知道这里少了一段");
    }

    @Test
    void parseIncludeOnlyRecognisesPathLikeTokens() {
        assertNull(InstructionLoader.parseInclude("@username"), "@username 不是路径引用");
        assertNull(InstructionLoader.parseInclude("@Bean"), "注解不应被当成引用");
        assertEquals("./a.md", InstructionLoader.parseInclude("@./a.md"));
        assertEquals("~/x.md", InstructionLoader.parseInclude("@~/x.md"));
        assertTrue(InstructionLoader.resolveInclude("~/x.md", project.toString())
                .startsWith(home.toString()), "~/ 应展开为用户 home");
        assertEquals("/abs/p.md", InstructionLoader.resolveInclude("/abs/p.md", project.toString()));
    }

    // ───────────────────────── 自动记忆 ─────────────────────────

    @Test
    void frontmatterParsesNameDescriptionType() {
        var fm = MemoryScanner.parseFrontmatter("""
                ---
                name: go-engineer
                description: 用户是 Go 工程师
                metadata:
                  type: user
                ---

                正文
                """);
        assertEquals("go-engineer", fm.name());
        assertEquals("用户是 Go 工程师", fm.description());
        assertEquals("user", fm.type());

        var bad = MemoryScanner.parseFrontmatter("""
                ---
                name: weird
                description: 类型不合法
                metadata:
                  type: whatever
                ---
                """);
        assertEquals("", bad.type(), "不合法的 type 应归一化为空串");
        assertTrue(MemoryScanner.isValidType("feedback"));
        assertFalse(MemoryScanner.isValidType("Feedbackx"));
    }

    @Test
    void extractionRoutesByTypeAndUpdatesIndexOnce() {
        var manager = new MemoryManager(project.toString());
        String sample = """
                MEMORY_NAME: go-engineer
                MEMORY_TYPE: user
                MEMORY_DESC: 用户是 Go 工程师
                MEMORY_BODY: 具备深入的 Go 语言专业知识，沟通可直接使用 Go 术语。
                ---
                MEMORY_NAME: build-command
                MEMORY_TYPE: project
                MEMORY_DESC: 构建 fat jar 的命令
                MEMORY_BODY: ./gradlew shadowJar
                """;
        manager.persistExtracted(sample);

        Path userFile = manager.userMemDir().resolve("go-engineer.md");
        Path projectFile = manager.projectMemDir().resolve("build-command.md");
        assertTrue(Files.exists(userFile), "user 类型应落到用户级目录");
        assertTrue(Files.exists(projectFile), "project 类型应落到项目级目录");

        String userIndex = read(manager.userMemDir().resolve("MEMORY.md"));
        assertTrue(userIndex.contains("(go-engineer.md)"), "索引应写入指针行");
        assertTrue(userIndex.contains("用户是 Go 工程师"));

        // 重复写入不该重复追加索引行
        manager.persistExtracted(sample);
        String again = read(manager.userMemDir().resolve("MEMORY.md"));
        assertEquals(1, countMatches(again, "(go-engineer.md)"),
                "同名记忆被更新时，索引不能追加重复行");
    }

    @Test
    void unknownTypeFallsBackToReference() {
        var manager = new MemoryManager(project.toString());
        manager.persistExtracted("""
                MEMORY_NAME: mystery
                MEMORY_TYPE: nonsense
                MEMORY_DESC: 类型不合法
                MEMORY_BODY: 内容
                """);
        String written = read(manager.projectMemDir().resolve("mystery.md"));
        assertTrue(written.contains("type: reference"), "非法类型应兜底为 reference");
    }

    @Test
    void extractFieldMatchesSingleLineOnly() {
        String block = "MEMORY_NAME: abc\nMEMORY_BODY: 第一行\n第二行\n";
        assertEquals("abc", MemoryManager.extractField(block, "MEMORY_NAME"));
        assertEquals("第一行", MemoryManager.extractField(block, "MEMORY_BODY"));
        assertEquals("", MemoryManager.extractField(block, "MEMORY_MISSING"));
    }

    @Test
    void indexTruncatesByLineLimitWithWarning() {
        var sb = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            sb.append("- [m").append(i).append("](m").append(i).append(".md) — 描述").append(i).append('\n');
        }
        String result = MemoryManager.truncateEntrypointContent(sb.toString());
        long lines = result.lines().filter(l -> l.startsWith("- [")).count();
        assertEquals(MemoryManager.MAX_ENTRYPOINT_LINES, lines, "超出部分应被按行截断");
        assertTrue(result.contains("WARNING"), "截断必须告诉模型索引是残缺的");
    }

    @Test
    void indexTruncatesByByteLimitWithoutSplittingUtf8() {
        // 中文索引正是常态：单个汉字 3 字节，若按字符截，25000 的上限实际会放过七万多字节
        String line = "记".repeat(30_000);
        byte[] raw = line.getBytes(StandardCharsets.UTF_8);
        assertTrue(raw.length > MemoryManager.MAX_ENTRYPOINT_BYTES * 3 / 2, "前提：原文确实超限");

        String cut = MemoryManager.cutToBytes(raw, MemoryManager.MAX_ENTRYPOINT_BYTES);
        byte[] bytes = cut.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= MemoryManager.MAX_ENTRYPOINT_BYTES,
                "字节数必须真的在上限内，实际 " + bytes.length);
        assertEquals(-1, cut.indexOf('\uFFFD'),
                "截断必须落在 UTF-8 字符边界上，不能解出乱码");
        assertTrue(cut.length() * 3 <= MemoryManager.MAX_ENTRYPOINT_BYTES + 3,
                "按字符截会放过三倍字节，这里必须没有");

        // 外层包装会在截断后追加 WARNING，告诉模型这份索引是残缺的
        assertTrue(MemoryManager.truncateEntrypointContent(line).contains("WARNING"));
    }

    @Test
    void injectionOnlyAppliesToEmptyConversation() throws IOException {
        Files.createDirectories(home.resolve(".agentjava").resolve("memory"));
        Files.writeString(home.resolve(".agentjava").resolve("memory").resolve("MEMORY.md"),
                "- [go-engineer](go-engineer.md) — 用户是 Go 工程师\n", StandardCharsets.UTF_8);

        var manager = new MemoryManager(project.toString());
        var conv = new ConversationManager();
        manager.injectMemories(conv);

        assertEquals(2, conv.size(), "应注入一条索引 user 消息 + 一条助手确认");
        assertTrue(conv.getMessages().get(0).getContent().contains("go-engineer"),
                "注入的是索引而不是全文");

        // 对话非空时不再注入，避免每轮重复堆积
        manager.injectMemories(conv);
        assertEquals(2, conv.size());
    }

    // ───────────────────────── 记忆召回 ─────────────────────────

    @Test
    void recallSelectsOnlyExistingFilesAndCapsAtFive() {
        var manager = new MemoryManager(project.toString());
        manager.persistExtracted("""
                MEMORY_NAME: go-engineer
                MEMORY_TYPE: user
                MEMORY_DESC: 用户是 Go 工程师
                MEMORY_BODY: 熟悉 Go 并发模型
                ---
                MEMORY_NAME: build-command
                MEMORY_TYPE: project
                MEMORY_DESC: 构建命令
                MEMORY_BODY: ./gradlew shadowJar
                """);
        MemoryRecall.SelectorFn selector = (system, user) ->
                "```json\n{\"selected_memories\": [\"go-engineer.md\", \"bogus.md\", \"build-command.md\"]}\n```";

        var found = MemoryRecall.findRelevantMemories("怎么构建这个项目？",
                manager.userMemDir(), manager.projectMemDir(),
                List.of("Bash"), java.util.Set.of(), selector);

        assertEquals(2, found.size(), "LLM 编造的文件名必须被候选清单过滤掉");
        assertTrue(found.stream().anyMatch(m -> "go-engineer.md".equals(m.filename())));
        assertTrue(found.stream().anyMatch(m -> "build-command.md".equals(m.filename())));
        assertTrue(found.stream().allMatch(m -> Files.exists(Path.of(m.path()))));
    }

    @Test
    void extractJsonObjectToleratesSurroundingProse() {
        assertEquals("{\"a\":1}", MemoryRecall.extractJsonObject("当然可以：\n{\"a\":1}\n希望有用"));
        assertEquals("{\"a\":{\"b\":\"}\"}}",
                MemoryRecall.extractJsonObject("{\"a\":{\"b\":\"}\"}}"),
                "字符串里的花括号不能参与平衡计数");
        assertEquals("", MemoryRecall.extractJsonObject("没有任何 JSON"));
    }

    @Test
    void renderReminderWarnsOnStaleMemory() throws IOException {
        var manager = new MemoryManager(project.toString());
        manager.persistExtracted("""
                MEMORY_NAME: old-note
                MEMORY_TYPE: project
                MEMORY_DESC: 一条很旧的观察
                MEMORY_BODY: 配置解析在 ConfigLoader.java:42
                """);
        Path file = manager.projectMemDir().resolve("old-note.md");
        Files.setLastModifiedTime(file,
                FileTime.fromMillis(System.currentTimeMillis() - 5L * 86_400_000L));

        assertEquals(5, MemoryAge.ageDays(Files.getLastModifiedTime(file).toMillis()));
        assertEquals("5 days ago", MemoryAge.age(Files.getLastModifiedTime(file).toMillis()));

        var found = List.of(new MemoryRecall.RelevantMemory(file.toString(), "old-note.md",
                Files.getLastModifiedTime(file).toMillis()));
        String rendered = MemoryRecall.renderReminder(found);
        assertTrue(rendered.contains("ConfigLoader.java:42"), "应渲染记忆全文");
        assertTrue(rendered.contains("saved 5 days ago"));
        assertTrue(rendered.contains("point-in-time observations"),
                "超过一天的记忆必须附验证提醒");
    }

    // ───────────────────────── 输入历史 ─────────────────────────

    @Test
    void historyDeduplicatesConsecutiveAndCapsAtMax() throws IOException {
        Path file = root.resolve("prompt_history.jsonl");
        var store = new HistoryStore(file);
        store.append("第一条");
        store.append("第一条");
        store.append("第二条");
        assertEquals(2, store.size(), "连续重复的输入只记一次");

        for (int i = 0; i < HistoryStore.MAX_ENTRIES + 20; i++) {
            store.append("cmd-" + i);
        }
        assertEquals(HistoryStore.MAX_ENTRIES, store.size(), "容量上限应为 MAX_ENTRIES");
        assertFalse(store.getEntries().contains("cmd-0"),
                "超出容量时从最老的那一端淘汰，cmd-0 应已不在");
        assertTrue(store.getEntries().contains("cmd-219"), "最新的记录必须还在");
        assertEquals(3, store.recent(3).size());

        var reloaded = new HistoryStore(file);
        assertEquals(HistoryStore.MAX_ENTRIES, reloaded.size(), "重新打开应读回全部历史");
        assertEquals("cmd-20", reloaded.recent(HistoryStore.MAX_ENTRIES).get(0),
                "淘汰后剩下的最老一条应为 cmd-20");
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static int countMatches(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }
}
