package com.agentjava.subagent;

import com.agentjava.config.ProviderConfig;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.conversation.Message;
import com.agentjava.conversation.ToolUseBlock;
import com.agentjava.llm.LlmClient;
import com.agentjava.llm.StreamEvent;
import com.agentjava.permission.PermissionMode;
import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolCategory;
import com.agentjava.tool.ToolRegistry;
import com.agentjava.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线验证：Agent 定义加载 → 统一 Agent 工具 → 两条执行路径 → 工具过滤 →
 * 后台任务 → 链路追踪。
 *
 * <p>定义文件全部走真实磁盘（{@link TempDir} + 替换 {@code user.home}），
 * LLM 用假客户端——这里要验证的是调度逻辑（过滤、分发、超时、取消、通知），
 * 不是模型输出，接真实 API 反而测不稳。
 */
class VerifySubAgent {

    @TempDir
    Path tempDir;

    private String originalHome;
    private Path project;
    private Path home;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        home = tempDir.resolve("home");
        project = tempDir.resolve("project");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    // ───────────────────────── 一、定义解析 ─────────────────────────

    @Test
    @DisplayName("定义文件：frontmatter 各字段读准，body 成为子 Agent 的系统提示")
    void parseAgentDefinition() throws IOException {
        Path file = project.resolve("reviewer.md");
        writeAgent(file, """
                ---
                name: Security-Reviewer
                description: 审查代码的安全问题
                tools: [ReadFile, Grep]
                disallowedTools: [Bash]
                model: haiku
                permissionMode: dontAsk
                maxTurns: 12
                ---

                你是安全审查专家。
                """);

        var def = AgentLoader.parseAgentFile(file, AgentDefinition.SOURCE_PROJECT);

        // name 小写化成 subagent_type 的取值
        assertEquals("security-reviewer", def.agentType());
        assertEquals("审查代码的安全问题", def.whenToUse());
        assertEquals(List.of("ReadFile", "Grep"), def.tools());
        assertEquals(List.of("Bash"), def.disallowedTools());
        assertEquals("haiku", def.model());
        // dontAsk 与 bypassPermissions 是同一语义：不弹审批窗口
        assertEquals(PermissionMode.BYPASS, def.permissionMode());
        assertEquals(12, def.maxTurns());
        assertEquals(12, def.effectiveMaxTurns());
        assertTrue(def.systemPrompt().contains("你是安全审查专家。"));
        assertEquals(AgentDefinition.SOURCE_PROJECT, def.source());
        assertTrue(def.isCustom());
    }

    @Test
    @DisplayName("定义文件：model: Inherit 标准化为小写，未写 maxTurns 走兜底 200 轮")
    void parseInheritAndDefaults() throws IOException {
        Path file = project.resolve("helper.md");
        writeAgent(file, """
                ---
                name: helper
                description: 通用帮手
                model: Inherit
                ---

                正文
                """);

        var def = AgentLoader.parseAgentFile(file, AgentDefinition.SOURCE_USER);

        assertEquals("inherit", def.model());
        assertEquals(AgentDefinition.DEFAULT_MAX_TURNS, def.effectiveMaxTurns());
        assertNull(def.permissionMode());
        assertEquals(AgentDefinition.SOURCE_USER, def.source());
    }

    @Test
    @DisplayName("定义文件：缺 name 或 description 直接报错——前者没法被选中，后者是派发依据")
    void parseRequiresNameAndDescription() throws IOException {
        Path noName = project.resolve("no-name.md");
        writeAgent(noName, "---\ndescription: 没有名字\n---\n\n正文");
        var e1 = assertThrows(IllegalArgumentException.class,
                () -> AgentLoader.parseAgentFile(noName, AgentDefinition.SOURCE_PROJECT));
        assertTrue(e1.getMessage().contains("name"));

        Path noDesc = project.resolve("no-desc.md");
        writeAgent(noDesc, "---\nname: no-desc\n---\n\n正文");
        var e2 = assertThrows(IllegalArgumentException.class,
                () -> AgentLoader.parseAgentFile(noDesc, AgentDefinition.SOURCE_PROJECT));
        assertTrue(e2.getMessage().contains("description"));
    }

    // ───────────────────────── 二、多来源加载 ─────────────────────────

    @Test
    @DisplayName("四级来源：项目级 > 用户级 > 内置级 > 插件级，同名后加载者赢")
    void loadOrderOverrides() throws IOException {
        Path plugin = tempDir.resolve("plugin").resolve("agents");
        writeAgent(plugin.resolve("plan.md"), """
                ---
                name: plan
                description: 插件级 plan（不该生效）
                ---
                插件正文
                """);
        writeAgent(home.resolve(".agentjava").resolve("agents").resolve("explore.md"), """
                ---
                name: explore
                description: 用户级 explore（该被项目级覆盖）
                ---
                用户正文
                """);
        writeAgent(home.resolve(".agentjava").resolve("agents").resolve("reviewer.md"), """
                ---
                name: reviewer
                description: 用户级审查员
                ---
                审查正文
                """);
        writeAgent(project.resolve(".agentjava").resolve("agents").resolve("explore.md"), """
                ---
                name: explore
                description: 项目级 explore（最终生效）
                ---
                项目正文
                """);

        var result = AgentLoader.loadAll(project, List.of(plugin));
        var defs = result.definitions();

        // 项目级压过用户级与内置级
        assertEquals("项目级 explore（最终生效）", defs.get("explore").whenToUse());
        assertEquals(AgentDefinition.SOURCE_PROJECT, defs.get("explore").source());
        // 用户级独有的照常加载
        assertEquals("用户级审查员", defs.get("reviewer").whenToUse());
        assertEquals(AgentDefinition.SOURCE_USER, defs.get("reviewer").source());
        // 内置级压过插件级：第三方插件不能覆盖系统内置定义
        assertEquals(AgentDefinition.PLAN.whenToUse(), defs.get("plan").whenToUse());
        assertEquals(AgentDefinition.SOURCE_BUILTIN, defs.get("plan").source());
        // 内置三个都在
        assertTrue(defs.containsKey("general-purpose"));
        assertTrue(result.errors().isEmpty());
        var names = AgentLoader.listNames(defs);
        assertEquals(4, names.size());
        assertEquals(Set.of("general-purpose", "plan", "explore", "reviewer"), new HashSet<>(names));
        // 顺序稳定：重复加载两次结果一致，工具 schema 的 enum 才不会每次渲染都变、打破 Prompt Cache
        assertEquals(names, AgentLoader.listNames(
                AgentLoader.loadAll(project, List.of(plugin)).definitions()));
    }

    @Test
    @DisplayName("坏定义文件：只丢那一个，其余照常加载，原因进 errors 供 /tasks 展示")
    void badFileDoesNotBreakOthers() throws IOException {
        Path dir = project.resolve(".agentjava").resolve("agents");
        writeAgent(dir.resolve("good.md"), """
                ---
                name: good
                description: 正常定义
                ---
                正文
                """);
        writeAgent(dir.resolve("broken.md"), """
                ---
                name: broken
                ---
                少了 description
                """);

        var result = AgentLoader.loadAll(project);

        assertTrue(result.definitions().containsKey("good"));
        assertFalse(result.definitions().containsKey("broken"));
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("broken.md"));
        // 内置的照旧可用
        assertTrue(result.definitions().containsKey("general-purpose"));
    }

    @Test
    @DisplayName("三个内置 Agent：能力边界与轮次上限符合设计约定")
    void builtinAgents() {
        var gp = AgentDefinition.GENERAL_PURPOSE;
        assertEquals("general-purpose", gp.agentType());
        assertEquals(200, gp.effectiveMaxTurns());
        assertTrue(gp.tools().isEmpty() && gp.disallowedTools().isEmpty());
        assertFalse(gp.isCustom());

        var plan = AgentDefinition.PLAN;
        assertEquals(15, plan.effectiveMaxTurns());
        // 只读：工具层禁写，系统提示层也强调
        assertTrue(plan.disallowedTools().containsAll(List.of("Agent", "EditFile", "WriteFile")));
        assertTrue(plan.systemPrompt().contains("严禁"));

        var explore = AgentDefinition.EXPLORE;
        assertEquals(30, explore.effectiveMaxTurns());
        assertEquals("haiku", explore.model());
        assertTrue(explore.disallowedTools().containsAll(List.of("EditFile", "WriteFile")));
    }

    // ───────────────────────── 三、工具过滤 ─────────────────────────

    @Test
    @DisplayName("四层过滤：全局禁止 → 自定义限制 → 后台白名单 → 定义层黑白名单")
    void toolFilterLayers() {
        var registry = fullRegistry();

        // 1) 全局禁止：Agent 与 AskUserQuestion 在任何子 Agent 里都拿不到
        var gp = ToolFilter.filterForAgent(registry, AgentDefinition.GENERAL_PURPOSE);
        assertNull(gp.get("Agent"), "子 Agent 拿不到 Agent 工具——定义式是调用链的终点");
        assertNull(gp.get("AskUserQuestion"), "交互式工具会让非交互循环卡死");
        assertNotNull(gp.get("ReadFile"));
        assertNotNull(gp.get("WriteFile"));

        // 2) 后台白名单：只剩基础读写工具
        var async = ToolFilter.filterForAgent(registry, AgentDefinition.GENERAL_PURPOSE, true);
        assertNull(async.get("Agent"), "后台任务不能再派子 Agent");
        assertEquals(7, async.listTools().size());

        // 3) 定义层白名单取交集
        var whitelist = new AgentDefinition("w", "白名单", List.of("ReadFile", "Grep"), List.of(),
                null, 0, null, null, null, AgentDefinition.SOURCE_PROJECT, null);
        var filtered = ToolFilter.filterForAgent(registry, whitelist);
        assertEquals(2, filtered.listTools().size());
        assertNotNull(filtered.get("ReadFile"));
        assertNotNull(filtered.get("Grep"));

        // 4) 白名单只有 * 时跳过交集，等价于「不限制」
        var star = new AgentDefinition("s", "星号", List.of("*"), List.of(),
                null, 0, null, null, null, AgentDefinition.SOURCE_PROJECT, null);
        assertEquals(7, ToolFilter.filterForAgent(registry, star).listTools().size());

        // 5) 定义层黑名单：在白名单之外再排除
        var blacklist = new AgentDefinition("b", "黑名单", List.of(), List.of("Bash"),
                null, 0, null, null, null, AgentDefinition.SOURCE_PROJECT, null);
        assertNull(ToolFilter.filterForAgent(registry, blacklist).get("Bash"));
    }

    @Test
    @DisplayName("后台白名单不含 Agent：后台层防资源失控 + 全局层防递归失控的叠加结果")
    void asyncWhitelistExcludesAgent() {
        assertFalse(ToolFilter.ASYNC_AGENT_ALLOWED_TOOLS.contains("Agent"));
        assertTrue(ToolFilter.ALL_AGENT_DISALLOWED_TOOLS.contains("Agent"));
    }

    @Test
    @DisplayName("Fork 复制全部工具并给 AgentTool 打 fork 标记：不过滤才能命中 prompt cache")
    void cloneForForkKeepsEverything() {
        var registry = fullRegistry();
        var forked = ToolFilter.cloneForFork(registry);

        // 不过滤：父 Agent 有什么它就有什么（含 Agent 工具本身）
        assertEquals(registry.listTools().size(), forked.listTools().size());
        assertNotNull(forked.get("Agent"));

        var cloned = forked.get("Agent");
        assertTrue(cloned instanceof AgentTool);
        assertEquals(AgentTool.FORK_QUERY_SOURCE, ((AgentTool) cloned).getQuerySource());
        assertEquals("", ((AgentTool) registry.get("Agent")).getQuerySource());
    }

    // ───────────────────────── 四、Fork 路径 ─────────────────────────

    @Test
    @DisplayName("Fork 会话：逐条复制父历史 + 补悬空 tool_use 的占位结果 + 末尾挂任务")
    void forkConversationCopiesHistory() {
        var parent = new ConversationManager();
        parent.addUserMessage("帮我看看这个项目");
        parent.addAssistantMessageWithTools("先读文件",
                List.of(new ToolUseBlock("tu_1", "ReadFile", Map.of("path", "README.md"))));

        var forked = AgentTool.forkConversation(parent, "Scope: 检查安全\nYour task:\n审查鉴权模块");

        // 父历史两条 + 任务一条（补位结果并进任务那条 user 消息）
        assertEquals(3, forked.size());
        assertTrue(forked.getMessages().get(0).getContent().contains("帮我看看这个项目"));
        assertTrue(forked.getMessages().get(1).getContent().contains("先读文件"));

        var last = forked.getMessages().get(2);
        assertEquals(Message.Role.USER, last.getRole());
        assertTrue(last.getContent().contains("Your task:"));
        // 悬空的 tool_use 必须有配对结果，否则 Anthropic 拒绝请求
        assertNotNull(last.getToolResults());
        assertTrue(last.getToolResults().stream().anyMatch(r -> "tu_1".equals(r.toolUseId())));
        assertEquals(ConversationManager.TOOL_RESULT_LOST_MESSAGE,
                last.getToolResults().get(0).content());
    }

    @Test
    @DisplayName("Fork 不能再 Fork：querySource 主检测 + 对话里的 boilerplate 兜底检测")
    void forkCannotNest() {
        // 第一道：标记跟着工具实例走，对话压缩影响不到
        var forkedTool = newTool(Map.of()).cloneWithQuerySource(AgentTool.FORK_QUERY_SOURCE);
        forkedTool.setParentConversation(new ConversationManager());
        var r1 = forkedTool.execute(Map.of("description", "嵌套", "prompt", "再分一个"));
        assertTrue(r1.isError());
        assertTrue(r1.output().contains("不能再从 Fork 子 Agent 里 Fork"));

        // 第二道：扫描对话里的 boilerplate（会被压缩摘要掉，所以只是兜底）
        var parent = new ConversationManager();
        parent.addUserMessage("<fork_boilerplate>\n1. Do NOT fork again.\n</fork_boilerplate>");
        var tool = newTool(Map.of());
        tool.setParentConversation(parent);
        var r2 = tool.execute(Map.of("description", "嵌套", "prompt", "再分一个"));
        assertTrue(r2.isError());
        assertTrue(r2.output().contains("不能再从 Fork 子 Agent 里 Fork"));
    }

    @Test
    @DisplayName("Fork：继承父上下文、总是后台，立即返回任务 ID")
    void forkAlwaysBackground() {
        var parent = new ConversationManager();
        parent.addUserMessage("分析一下这个仓库");
        var tool = newTool(Map.of());
        tool.setParentConversation(parent);
        var manager = tool.getTaskManager();

        var result = tool.execute(Map.of("description", "并行分析", "prompt", "统计测试覆盖率"));

        assertFalse(result.isError(), result.output());
        assertTrue(result.output().contains("已在后台启动"));
        assertTrue(result.output().contains("task_1"));
        // 走的是 Fork 路径，不是定义式
        var task = manager.getTask("task_1");
        assertNotNull(task);
        assertEquals("fork", task.agentType());
        assertTrue(task.name().startsWith("fork:"));
        // 会话副本里带上了 boilerplate
        assertNotNull(tool.getTrace().get("task_1"));
        assertEquals(AgentTool.PATH_FORK, tool.getTrace().get("task_1").path());
    }

    // ───────────────────────── 五、RunToCompletion ─────────────────────────

    @Test
    @DisplayName("定义式同步执行：跑完整个工具循环，把最终文本作为工具结果返回")
    void runToCompletionReturnsFinalText() {
        var tool = newTool(builtins(), new FakeClient("最终结论：鉴权入口在 AuthFilter", null, true));
        var manager = tool.getTaskManager();

        var result = tool.execute(Map.of(
                "description", "审查鉴权",
                "prompt", "找出鉴权入口",
                "subagent_type", "general-purpose"));

        assertFalse(result.isError(), result.output());
        assertTrue(result.output().contains("已完成"));
        assertTrue(result.output().contains("最终结论：鉴权入口在 AuthFilter"));
        assertTrue(result.output().contains("准备读取"), "中间轮的文本也要累计进最终输出");

        var task = manager.getTask("task_1");
        assertEquals(SubAgentTaskManager.TaskStatus.COMPLETED, task.status());

        // 链路追踪：两轮用量累加、轮次、状态
        var span = tool.getTrace().get("task_1");
        assertEquals("general-purpose", span.agentType());
        assertEquals(AgentTool.PATH_DEFINITION, span.path());
        assertEquals(1, span.depth());
        assertEquals(TraceRegistry.Status.COMPLETED, span.status());
        assertEquals(250, span.inputTokens(), "UsageEvent 是累计值，追踪里直接覆盖");
        assertEquals(45, span.outputTokens());
        assertEquals(1, span.turns());
    }

    @Test
    @DisplayName("后台执行：立即返回，跑完后通知进队列供父 Agent 拉取")
    void backgroundTaskNotifiesLater() {
        var tool = newTool(builtins(), new FakeClient("后台结论", null, false));
        var manager = tool.getTaskManager();

        var result = tool.execute(Map.of(
                "description", "后台调研",
                "prompt", "调研一下",
                "subagent_type", "explore",
                "run_in_background", true));

        assertFalse(result.isError());
        assertTrue(result.output().contains("已在后台启动"));
        assertTrue(result.output().contains("task-notification"));

        var notes = waitForNotifications(manager);
        assertEquals(1, notes.size());
        assertEquals(SubAgentTaskManager.TaskStatus.COMPLETED, notes.get(0).status());
        assertTrue(notes.get(0).output().contains("后台结论"));
        assertEquals("explore", notes.get(0).agentType());
        // 拉模式：取完即清空
        assertTrue(manager.drainNotifications().isEmpty());
    }

    @Test
    @DisplayName("ESC 切后台：只中断等待线程，worker 继续跑，父 Agent 不会被一起带走")
    void escapeSwitchesToBackground() throws Exception {
        var hold = new CountDownLatch(1);
        var tool = newTool(builtins(), new FakeClient("慢任务结论", hold, false));
        var manager = tool.getTaskManager();

        var ref = new AtomicReference<ToolResult>();
        Thread caller = Thread.ofVirtual().start(() -> ref.set(tool.execute(Map.of(
                "description", "慢活", "prompt", "慢慢做", "subagent_type", "general-purpose"))));

        String switched = pollUntilBackgroundSwitchable(manager);
        assertNotNull(switched, "前台等待的线程应该被登记上，ESC 才能切");
        hold.countDown();
        caller.join();

        var result = ref.get();
        assertNotNull(result);
        assertFalse(result.isError(), "切后台不是失败");
        assertTrue(result.output().contains("已转入后台继续运行"));
        assertEquals(TraceRegistry.Status.BACKGROUND, tool.getTrace().get("task_1").status());
        // 任务最终还是会跑完，结果走通知回传
        var notes = waitForNotifications(manager);
        assertEquals(SubAgentTaskManager.TaskStatus.COMPLETED, notes.get(0).status());
    }

    @Test
    @DisplayName("取消是终态：worker 被中断后的收尾不能把状态覆盖回失败")
    void cancelIsTerminal() throws Exception {
        var hold = new CountDownLatch(1);
        var tool = newTool(builtins(), new FakeClient("不该产出", hold, false));
        var manager = tool.getTaskManager();

        var ref = new AtomicReference<ToolResult>();
        Thread caller = Thread.ofVirtual().start(() -> ref.set(tool.execute(Map.of(
                "description", "待取消", "prompt", "做点什么", "subagent_type", "general-purpose"))));

        String id = pollUntilRunning(manager);
        assertTrue(manager.cancelTask(id));
        assertEquals(SubAgentTaskManager.TaskStatus.CANCELLED, manager.getTask(id).status());

        hold.countDown();
        caller.join();

        assertTrue(ref.get().isError());
        assertTrue(ref.get().output().contains("已被取消"));
        // worker 醒过来后的 setFailed 不能覆盖已取消的状态
        assertEquals(SubAgentTaskManager.TaskStatus.CANCELLED, manager.getTask(id).status());
        var notes = manager.drainNotifications();
        assertEquals(SubAgentTaskManager.TaskStatus.CANCELLED, notes.get(0).status());
    }

    // ───────────────────────── 六、参数校验与边界 ─────────────────────────

    @Test
    @DisplayName("参数校验：必填项、未知类型、已实现的 worktree 隔离被识别")
    void argumentValidation() {
        var tool = newTool(builtins(), new FakeClient("x", null, false));

        var missing = tool.execute(Map.of("prompt", "只有 prompt"));
        assertTrue(missing.isError());
        assertTrue(missing.output().contains("description 和 prompt 都是必填参数"));

        var unknown = tool.execute(Map.of("description", "d", "prompt", "p", "subagent_type", "nope"));
        assertTrue(unknown.isError());
        assertTrue(unknown.output().contains("未知的 subagent_type"));
        assertTrue(unknown.output().contains("general-purpose"));

        // worktree 隔离已实现：isolation=worktree 现在被识别为合法模式，
        // 不再是「未实现」的占位错误。测试脚手架没注入 WorktreeManager，因此给出明确的
        // 「未初始化」提示，而不是崩溃或静默走 Fork 路径。
        var worktree = tool.execute(Map.of("description", "d", "prompt", "p", "isolation", "worktree"));
        assertTrue(worktree.isError());
        assertTrue(worktree.output().contains("Worktree 管理器"));
    }

    @Test
    @DisplayName("工具契约：名字固定为 Agent，类型清单进描述与 schema 的 enum")
    void toolContract() {
        var defs = builtins();
        defs.put("reviewer", new AgentDefinition("reviewer", "安全审查", List.of(), List.of(),
                null, 0, null, null, null, AgentDefinition.SOURCE_PROJECT, null));
        var tool = newTool(defs, new FakeClient("x", null, false));

        assertEquals("Agent", tool.name());
        assertEquals(ToolCategory.COMMAND, tool.category());

        @SuppressWarnings("unchecked")
        var schema = (Map<String, Object>) tool.schema().get("input_schema");
        assertEquals(List.of("description", "prompt"), schema.get("required"));
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("subagent_type"));
        assertTrue(props.containsKey("run_in_background"));
        assertTrue(props.containsKey("isolation"));

        @SuppressWarnings("unchecked")
        var typeSchema = (Map<String, Object>) props.get("subagent_type");
        @SuppressWarnings("unchecked")
        var enums = (List<String>) typeSchema.get("enum");
        assertTrue(enums.contains("reviewer"), "自定义类型要出现在可选项里");
        assertTrue(tool.description().contains("安全审查"), "描述是主 Agent 判断派谁的唯一依据");
    }

    @Test
    @DisplayName("定义顺序稳定：注入后仍保持加载顺序，schema 的 enum 才不会每次渲染都变")
    void definitionOrderIsStable() {
        var defs = new LinkedHashMap<String, AgentDefinition>();
        defs.put("zzz-last", AgentDefinition.GENERAL_PURPOSE);
        defs.put("aaa-first", AgentDefinition.PLAN);

        var tool = newTool(Map.of());
        tool.setAgentDefinitions(defs);

        assertEquals(List.of("zzz-last", "aaa-first"),
                AgentLoader.listNames(tool.getAgentDefinitions()),
                "Map.copyOf 会打乱顺序，这里必须用保序的不可变包装");
    }

    @Test
    @DisplayName("agent 类型 Hook 的执行器：同步跑一个通用子 Agent 拿最终文本")
    void hookAgentRunner() {
        var tool = newTool(builtins(), new FakeClient("Hook 子 Agent 的结论", null, false));

        var output = tool.runHookAgent("检查一下改动");

        assertTrue(output.contains("Hook 子 Agent 的结论"), output);
        // 开关要能复位，否则第二次 Hook 就被永久挡住
        assertTrue(tool.runHookAgent("再检查一次").contains("Hook 子 Agent 的结论"));
    }

    @Test
    @DisplayName("agent 类型 Hook 不能递归：子 Agent 里再触发时直接忽略，且不死锁")
    void hookAgentRunnerIsNotReentrant() {
        var client = new FakeClient("外层结论", null, false);
        var tool = newTool(builtins(), client);
        var nested = new AtomicReference<String>();
        // 模拟「子 Agent 跑的过程中又撞上同一条 agent Hook」：回调发生在 worker 线程上
        client.onStream = () -> nested.set(tool.runHookAgent("嵌套调用"));

        var output = tool.runHookAgent("外层调用");

        assertTrue(output.contains("外层结论"), output);
        assertEquals("（嵌套调用已忽略：agent 类型 Hook 不能在子 Agent 里再起子 Agent）",
                nested.get(), "第二层必须立刻返回，否则父子互相等对方直接死锁");
    }

    // ───────────────────────── 七、链路追踪 ─────────────────────────

    @Test
    @DisplayName("调用链：Fork 下的定义式子 Agent 深度为 2，token 与轮次按 span 累计")
    void traceChain() {
        var trace = new TraceRegistry();
        trace.start("task_1", null, "fork", null, AgentTool.PATH_FORK);
        trace.start("task_2", "task_1", "explore", "haiku", AgentTool.PATH_DEFINITION);

        trace.onEvent("task_2", new com.agentjava.agent.AgentEvent.UsageEvent(800, 120));
        trace.onEvent("task_2", new com.agentjava.agent.AgentEvent.TurnComplete(3));
        trace.finish("task_2", TraceRegistry.Status.COMPLETED);

        var child = trace.get("task_2");
        assertEquals(2, child.depth());
        assertEquals(800, child.inputTokens());
        assertEquals(3, child.turns());
        assertEquals("Fork", trace.get("task_1").pathLabel());
        assertEquals("定义式", child.pathLabel());
        assertEquals("inherit", trace.get("task_1").modelLabel());

        assertEquals(2, trace.subtree("task_1").size(), "父 span 要能带出整棵子树");
        assertTrue(trace.render().contains("task_1"));
        assertTrue(trace.render().contains("task_2"));
        assertTrue(trace.summary().contains("子 Agent 调用 2 次"));
        assertTrue(trace.summary().contains("↑800"), trace.summary());

        // 后台任务的结局从 task-notification 回填
        assertEquals(TraceRegistry.Status.COMPLETED,
                TraceRegistry.fromTaskStatus(SubAgentTaskManager.TaskStatus.COMPLETED));
        assertEquals(TraceRegistry.Status.RUNNING,
                TraceRegistry.fromTaskStatus(SubAgentTaskManager.TaskStatus.RUNNING));
    }

    // ───────────────────────── 辅助 ─────────────────────────

    /** 父 Agent 的完整工具集：六个内置 + LoadSkill + Agent + 一个交互式工具。 */
    private static ToolRegistry fullRegistry() {
        var reg = new ToolRegistry();
        reg.register(new FakeTool("ReadFile", "读文件", ToolCategory.READ, "文件内容"));
        reg.register(new FakeTool("WriteFile", "写文件", ToolCategory.WRITE, "已写入"));
        reg.register(new FakeTool("EditFile", "改文件", ToolCategory.WRITE, "已修改"));
        reg.register(new FakeTool("Bash", "跑命令", ToolCategory.COMMAND, "命令输出"));
        reg.register(new FakeTool("Glob", "匹配文件", ToolCategory.READ, "a.java"));
        reg.register(new FakeTool("Grep", "搜内容", ToolCategory.READ, "命中 1 处"));
        reg.register(new FakeTool("LoadSkill", "加载技能", ToolCategory.READ, "技能正文"));
        reg.register(new FakeTool("AskUserQuestion", "问用户", ToolCategory.READ, "用户回答"));
        reg.register(new AgentTool(reg));
        return reg;
    }

    /** 内置三件套的副本（可变，方便往里塞自定义定义）。 */
    private static LinkedHashMap<String, AgentDefinition> builtins() {
        var map = new LinkedHashMap<String, AgentDefinition>();
        for (var def : AgentDefinition.builtins()) {
            map.put(def.agentType(), def);
        }
        return map;
    }

    /** 装配一个可直接调用的 AgentTool：假客户端 + 任务管理器 + 追踪 + 父会话。 */
    private AgentTool newTool(Map<String, AgentDefinition> defs, FakeClient client) {
        var registry = fullRegistry();
        var manager = new SubAgentTaskManager();
        var trace = new TraceRegistry();
        var tool = new AgentTool(registry);
        tool.setAgentDefinitions(defs);
        tool.setClient(client);
        tool.setTaskManager(manager);
        tool.setTrace(trace);
        tool.setParentConversation(new ConversationManager());
        tool.setWorkDir(project.toString());
        // 避免 DEFAULT 权限模式在测试里弹审批（PermissionRequestEvent 会阻塞住循环）
        tool.setPermissionMode(PermissionMode.BYPASS);
        return tool;
    }

    private AgentTool newTool(Map<String, AgentDefinition> defs) {
        return newTool(defs, new FakeClient("默认结论", null, false));
    }

    private static void writeAgent(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static List<SubAgentTaskManager.TaskNotification> waitForNotifications(
            SubAgentTaskManager manager) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            var notes = manager.drainNotifications();
            if (!notes.isEmpty()) {
                return notes;
            }
            sleep(20);
        }
        return List.of();
    }

    /** 等到任务真正进入 RUNNING——取消要打在已经跑起来的 worker 上才有意义。 */
    private static String pollUntilRunning(SubAgentTaskManager manager) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            var tasks = manager.listTasks();
            if (!tasks.isEmpty() && tasks.get(0).status() == SubAgentTaskManager.TaskStatus.RUNNING) {
                return tasks.get(0).id();
            }
            sleep(10);
        }
        return "task_1";
    }

    private static String pollUntilBackgroundSwitchable(SubAgentTaskManager manager) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            String id = manager.switchForegroundToBackground();
            if (id != null) {
                return id;
            }
            sleep(10);
        }
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 只按名字区分的假工具，用来验证过滤逻辑而不是执行逻辑。 */
    private record FakeTool(String name, String description, ToolCategory category, String result)
            implements Tool {

        @Override
        public Map<String, Object> schema() {
            return Map.of("name", name, "description", description,
                    "input_schema", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            return ToolResult.success(result);
        }
    }

    /**
     * 假 LLM 客户端：不联网，按脚本吐事件。
     *
     * <p>{@code hold} 用来模拟「上游很慢」——测试 ESC 切后台与取消都需要在子 Agent
     * 卡住的时刻介入。
     */
    private static final class FakeClient implements LlmClient {

        private final ProviderConfig cfg = new ProviderConfig(
                "test", "anthropic", "http://127.0.0.1:1/v1", "test-key", "test-model", false);
        private final String finalText;
        private final CountDownLatch hold;
        private final boolean withToolCall;
        private int calls;

        /** 每次发起请求时的回调，用来在子 Agent 执行过程中插入别的行为（如递归 Hook）。 */
        Runnable onStream;

        FakeClient(String finalText, CountDownLatch hold, boolean withToolCall) {
            this.finalText = finalText;
            this.hold = hold;
            this.withToolCall = withToolCall;
        }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv) {
            return stream(conv, List.of());
        }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv,
                                                 List<Map<String, Object>> tools) {
            calls++;
            if (onStream != null) {
                onStream.run();
            }
            if (hold != null) {
                try {
                    hold.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            var queue = new LinkedBlockingQueue<StreamEvent>();
            if (withToolCall && calls == 1) {
                queue.add(new StreamEvent.TextDelta("准备读取"));
                queue.add(new StreamEvent.ToolCallStart("tu_1", "ReadFile"));
                queue.add(new StreamEvent.ToolCallComplete("tu_1", "ReadFile",
                        Map.of("path", "README.md")));
                queue.add(new StreamEvent.StreamEnd("tool_use", 100, 20, 0, 0));
            } else {
                queue.add(new StreamEvent.TextDelta(finalText));
                queue.add(new StreamEvent.StreamEnd("end_turn", 150, 25, 0, 0));
            }
            return queue;
        }

        @Override
        public ProviderConfig config() {
            return cfg;
        }

        @Override
        public void setSystemPrompt(String prompt) {
            // 测试不关心系统提示的渲染
        }
    }
}
