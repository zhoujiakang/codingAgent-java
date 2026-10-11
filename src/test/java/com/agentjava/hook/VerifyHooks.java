package com.agentjava.hook;

import com.agentjava.agent.AgentEvent;
import com.agentjava.agent.StreamingExecutor;
import com.agentjava.permission.PermissionChecker;
import com.agentjava.permission.PermissionMode;
import com.agentjava.tool.ToolRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线验证：Hook 生命周期钩子。
 *
 * <p>覆盖三件事：条件语法（四种操作符 + 组合 + 不可混用）、四种执行器与执行控制
 * （once / async / timeout / reject 短路）、以及配置加载与校验（三处追加合并、坏规则定位）。
 * 最后用一条和实战篇完全一样的 {@code block-json-write} 规则，走真实的
 * {@link StreamingExecutor} 验一遍「拦截 → 工具没执行 → 拒绝原因回灌」这条链路。
 *
 * <p>全部离线：HTTP 执行器用 JDK 自带的 {@link HttpServer} 起一个 localhost 服务，
 * 不连外网。
 */
class VerifyHooks {

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

    // ───────────────────────── 条件语法 ─────────────────────────

    @Test
    @DisplayName("四种操作符：== 精确、!= 反向、=~ 正则、~= glob")
    void fourOperators() {
        var ctx = ctx("WriteFile", Map.of("file_path", "/tmp/proj/config.json"));

        assertTrue(eval("tool == \"WriteFile\"", ctx));
        assertFalse(eval("tool == \"ReadFile\"", ctx));
        assertTrue(eval("tool != \"ReadFile\"", ctx));
        assertFalse(eval("tool != \"WriteFile\"", ctx));
        assertTrue(eval("args.file_path =~ /\\.json$/", ctx));
        assertFalse(eval("args.file_path =~ /\\.ya?ml$/", ctx));
    }

    @Test
    @DisplayName("正则的 /.../ 是分隔符不是模式本体，剥掉才匹配得上")
    void regexDelimitersAreStripped() {
        var ctx = ctx("WriteFile", Map.of("file_path", "/tmp/proj/config.json"));
        // 不剥斜杠的话，/\.json$/ 会被当成「字面 / + .json + 行尾 + 字面 /」，永远匹配不上
        assertTrue(eval("args.file_path =~ /\\.json$/", ctx));
        assertEquals("\\.json$", Condition.parse("args.file_path =~ /\\.json$/").value());
    }

    @Test
    @DisplayName("glob 的 * 不跨目录分隔符，绝对路径要按后缀匹配得用正则")
    void globDoesNotCrossSeparator() {
        assertTrue(eval("args.path ~= \"*.py\"", ctx("WriteFile", Map.of("path", "main.py"))));
        assertFalse(eval("args.path ~= \"*.py\"", ctx("WriteFile", Map.of("path", "sub/main.py"))));
        // 这正是实战篇里那条规则用 =~ /\.json$/ 而不是 ~= "*.json" 的原因
        assertFalse(eval("args.path ~= \"*.json\"", ctx("WriteFile", Map.of("path", "/tmp/a/config.json"))));
        assertTrue(eval("args.path =~ /\\.json$/", ctx("WriteFile", Map.of("path", "/tmp/a/config.json"))));
    }

    @Test
    @DisplayName("&& 全满足、|| 任一满足")
    void andOrGroups() {
        var py = ctx("WriteFile", Map.of("path", "main.py"));
        var go = ctx("WriteFile", Map.of("path", "main.go"));

        assertTrue(eval("tool == \"WriteFile\" && args.path ~= \"*.py\"", py));
        assertFalse(eval("tool == \"WriteFile\" && args.path ~= \"*.py\"", go));
        assertTrue(eval("tool == \"ReadFile\" || args.path ~= \"*.go\"", go));
        assertFalse(eval("tool == \"ReadFile\" || args.path ~= \"*.go\"", py));
    }

    @Test
    @DisplayName("&& 与 || 不能混用：混了要在解析期报错，而不是悄悄算错")
    void mixingAndOrIsRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ConditionGroup.parse("tool == \"A\" && args.x == \"1\" || args.y == \"2\""));
        assertTrue(ex.getMessage().contains("不能混用"), ex.getMessage());
    }

    @Test
    @DisplayName("正则里出现 && 不能被当成组合符切开")
    void separatorsInsideRegexAreIgnored() {
        var ctx = ctx("Bash", Map.of("command", "git log && grep fix"));
        assertTrue(eval("args.command =~ /&&/", ctx));
        assertTrue(eval("tool == \"Bash\" && args.command =~ /&&/", ctx));
    }

    @Test
    @DisplayName("只写字段名：非空即真；未知字段返回空串，不报错")
    void bareFieldAndUnknownField() {
        assertTrue(eval("tool", ctx("Bash", Map.of())));
        assertFalse(eval("tool", ctx(null, Map.of())));
        assertFalse(eval("args.nope == \"x\"", ctx("Bash", Map.of())));
        // 正则写错只当不匹配，不抛异常
        assertFalse(eval("tool =~ /[unclosed/", ctx("Bash", Map.of())));
    }

    @Test
    @DisplayName("无条件（省略 if）恒真")
    void emptyConditionAlwaysMatches() {
        assertTrue(ConditionGroup.ALWAYS.matches(ctx("Bash", Map.of())));
        assertTrue(ConditionGroup.parse("  ").isEmpty());
    }

    // ───────────────────────── 上下文变量 ─────────────────────────

    @Test
    @DisplayName("$EVENT / $TOOL_NAME / $FILE_PATH / $MESSAGE / $ERROR / $TOOL_ARGS.xxx 逐个替换")
    void expandVariables() {
        var ctx = new HookContext(EventName.POST_TOOL_USE, "WriteFile",
                Map.of("file_path", "/tmp/a.py"), "/tmp/a.py", "写完了", null);
        assertEquals("event=post_tool_use tool=WriteFile path=/tmp/a.py msg=写完了 arg=/tmp/a.py",
                ctx.expand("event=$EVENT tool=$TOOL_NAME path=$FILE_PATH msg=$MESSAGE arg=$TOOL_ARGS.file_path"));
    }

    @Test
    @DisplayName("未定义的 $TOOL_ARGS.xxx 兜底成空串；shell 自己的 $HOME 不能被误伤")
    void expandUnknownArgsAndShellVars() {
        var ctx = new HookContext(EventName.POST_TOOL_USE, "WriteFile", Map.of(), null, null, null);
        assertEquals("[]", ctx.expand("[$TOOL_ARGS.nope]"));
        assertEquals("$HOME", ctx.expand("$HOME"));
        // $ERROR 在没有错误时也是空串，不是留下占位符
        assertEquals("x", ctx.expand("x$ERROR"));
    }

    // ───────────────────────── 事件与动作枚举 ─────────────────────────

    @Test
    @DisplayName("15 个生命周期事件都能按名字反查；pre_tool_use 是唯一可拦截的")
    void eventNames() {
        String[] expected = {
                "session_start", "session_end", "turn_start", "turn_end",
                "pre_tool_use", "post_tool_use", "pre_send", "post_receive",
                "startup", "shutdown", "error", "compact",
                "permission_request", "file_change", "command_execute"
        };
        assertEquals(15, EventName.values().length);
        for (String name : expected) {
            assertEquals(name, EventName.fromValue(name).orElseThrow().value());
        }
        assertTrue(EventName.PRE_TOOL_USE.isIntercepting());
        for (EventName e : EventName.values()) {
            if (e != EventName.PRE_TOOL_USE) {
                assertFalse(e.isIntercepting(), e.value());
            }
        }
        assertTrue(EventName.fromValue("nope").isEmpty());
    }

    // ───────────────────────── 执行器 ─────────────────────────

    @Test
    @DisplayName("command：拿到 stdout，返回码 0 才算成功")
    void commandExecutor() {
        HookEngine engine = new HookEngine();
        engine.register(notifyHook("echo-cmd", command("echo hello")));
        var results = engine.runHooks(HookContext.of(EventName.POST_TOOL_USE));
        assertEquals(1, results.size());
        assertEquals("hello", results.get(0).output());
        assertTrue(results.get(0).success());
    }

    @Test
    @DisplayName("command：非 0 返回码算失败，输出照样带回来")
    void commandNonZeroExitIsFailure() {
        HookEngine engine = new HookEngine();
        engine.register(notifyHook("fail-cmd", command("echo boom; exit 3")));
        var results = engine.runHooks(HookContext.of(EventName.POST_TOOL_USE));
        assertFalse(results.get(0).success());
        assertEquals("boom", results.get(0).output());
    }

    @Test
    @DisplayName("command：超时强杀并返回失败，不把主流程挂住")
    void commandTimeout() {
        HookEngine engine = new HookEngine();
        Action timed = new Action(ActionType.COMMAND, "sleep 5", null, null, null, null, null,
                Duration.ofMillis(300));
        engine.register(notifyHook("slow", timed));
        var results = engine.runHooks(HookContext.of(EventName.POST_TOOL_USE));
        assertFalse(results.get(0).success());
        assertTrue(results.get(0).output().contains("timed out"), results.get(0).output());
    }

    @Test
    @DisplayName("prompt：原样返回文本，并标记为可注入对话的通知")
    void promptExecutor() {
        HookEngine engine = new HookEngine();
        engine.register(new Hook("ctx", EventName.SESSION_START, ConditionGroup.ALWAYS,
                prompt("项目技术栈：Java 21 + Gradle"), false, false, false, null));
        var results = engine.runHooks(HookContext.of(EventName.SESSION_START));
        assertEquals("项目技术栈：Java 21 + Gradle", results.get(0).output());
        assertTrue(results.get(0).isPromptNotification());
        // 注入后队列被取走，不会重复注入
        assertEquals(1, engine.drainNotifications().size());
        assertEquals(0, engine.drainNotifications().size());
    }

    @Test
    @DisplayName("agent：未注册 runner 时返回明确说明，不是 NPE")
    void agentExecutorIsPlaceholder() {
        HookEngine engine = new HookEngine();
        engine.register(new Hook("sub", EventName.POST_TOOL_USE, ConditionGroup.ALWAYS,
                agent("帮我复查这个文件"), false, false, false, null));
        var results = engine.runHooks(HookContext.of(EventName.POST_TOOL_USE));
        assertFalse(results.get(0).success());
        assertTrue(results.get(0).output().contains("AgentRunner"), results.get(0).output());
    }

    @Test
    @DisplayName("agent：注册了 runner 之后走回调，保持 Hook 包对 Agent 模块零依赖")
    void agentExecutorUsesRunner() {
        HookEngine engine = new HookEngine();
        engine.setAgentRunner((promptText, ctx) -> "子 Agent 已处理：" + promptText);
        engine.register(new Hook("sub", EventName.POST_TOOL_USE, ConditionGroup.ALWAYS,
                agent("复查 $TOOL_NAME"), false, false, false, null));
        var results = engine.runHooks(HookContext.ofTool(EventName.POST_TOOL_USE, "WriteFile", Map.of()));
        assertTrue(results.get(0).success());
        assertEquals("子 Agent 已处理：复查 WriteFile", results.get(0).output());
    }

    @Test
    @DisplayName("http：POST 到外部系统，响应体带回来；大响应会被截断")
    void httpExecutor() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var received = new String[1];
        server.createContext("/hook", exchange -> {
            received[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            HookEngine engine = new HookEngine();
            Action action = new Action(ActionType.HTTP, null, null, url, "POST",
                    Map.of("X-Event", "$EVENT"), "{\"file\":\"$TOOL_ARGS.file_path\"}",
                    Duration.ofSeconds(5));
            engine.register(new Hook("notify", EventName.POST_TOOL_USE, ConditionGroup.ALWAYS,
                    action, false, false, false, null));
            var results = engine.runHooks(
                    HookContext.ofTool(EventName.POST_TOOL_USE, "WriteFile", Map.of("file_path", "/tmp/a.py")));

            assertTrue(results.get(0).success(), results.get(0).output());
            assertEquals("ok", results.get(0).output());
            assertEquals("{\"file\":\"/tmp/a.py\"}", received[0]);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("http：没配 body 时自动把上下文序列化成 JSON")
    void httpDefaultPayload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var received = new String[1];
        server.createContext("/hook", exchange -> {
            received[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            HookEngine engine = new HookEngine();
            engine.register(new Hook("notify", EventName.FILE_CHANGE, ConditionGroup.ALWAYS,
                    http(url), false, false, false, null));
            engine.runHooks(HookContext.ofFile(EventName.FILE_CHANGE, "/tmp/a.py"));
            assertNotNull(received[0]);
            assertTrue(received[0].contains("\"event\":\"file_change\""), received[0]);
            assertTrue(received[0].contains("\"file_path\":\"/tmp/a.py\""), received[0]);
        } finally {
            server.stop(0);
        }
    }

    // ───────────────────────── 执行控制 ─────────────────────────

    @Test
    @DisplayName("once：第一次触发后不再触发；resetExecuted 之后又能跑")
    void onceOnlyFiresOnce() {
        HookEngine engine = new HookEngine();
        Hook h = new Hook("greet", EventName.TURN_START, ConditionGroup.ALWAYS,
                prompt("欢迎"), false, true, false, null);
        engine.register(h);

        assertEquals(1, engine.runHooks(HookContext.of(EventName.TURN_START)).size());
        assertEquals(0, engine.runHooks(HookContext.of(EventName.TURN_START)).size());
        assertTrue(h.isExecuted());

        engine.resetExecuted();
        assertEquals(1, engine.runHooks(HookContext.of(EventName.TURN_START)).size());
    }

    @Test
    @DisplayName("async：主流程立刻拿到占位结果，真正的输出稍后进通知队列")
    void asyncRunsInBackground() throws Exception {
        HookEngine engine = new HookEngine();
        engine.register(new Hook("slow-notify", EventName.POST_TOOL_USE, ConditionGroup.ALWAYS,
                command("sleep 1; echo done"), false, false, true, null));

        var results = engine.runHooks(HookContext.of(EventName.POST_TOOL_USE));
        assertEquals(1, results.size());
        assertEquals("(async)", results.get(0).output());

        List<HookResult> drained = List.of();
        for (int i = 0; i < 50 && drained.isEmpty(); i++) {
            Thread.sleep(100);
            drained = engine.drainNotifications();
        }
        assertEquals(1, drained.size(), "异步结果应当最终进入通知队列");
        assertEquals("done", drained.get(0).output());
    }

    @Test
    @DisplayName("错误兜底：动作执行出错只记日志，绝不打断 Agent 主流程")
    void errorsDoNotBreakTheLoop() {
        HookEngine engine = new HookEngine();
        var logged = new java.util.ArrayList<String>();
        engine.setLogger(logged::add);
        // command 为 null → 拼命令时炸，引擎要接住
        engine.register(notifyHook("broken", command(null)));

        var results = engine.runHooks(HookContext.of(EventName.POST_TOOL_USE));
        assertEquals(1, results.size());
        assertFalse(results.get(0).success());
        assertFalse(logged.isEmpty(), "出错要留痕，方便 /hooks 排查");
    }

    @Test
    @DisplayName("reject：pre_tool_use 上命中即返回 ToolRejectedError，原因取动作输出")
    void rejectReturnsToolRejectedError() {
        HookEngine engine = new HookEngine();
        engine.register(preHook("block-json-write",
                command("echo \"禁止直接写入 JSON 文件，请使用专用工具\""), true));

        var rejected = engine.runPreToolHooks(
                HookContext.ofTool(EventName.PRE_TOOL_USE, "WriteFile",
                        Map.of("file_path", "/tmp/a/config.json")));
        assertTrue(rejected.isPresent());
        assertEquals("WriteFile", rejected.get().toolName());
        assertEquals("禁止直接写入 JSON 文件，请使用专用工具", rejected.get().reason());
        assertEquals("block-json-write", rejected.get().hookId());
    }

    @Test
    @DisplayName("reject：动作没输出时补一句兜底话术，别让 LLM 收到空原因")
    void rejectFallsBackToDefaultMessage() {
        HookEngine engine = new HookEngine();
        engine.register(preHook("silent-block", command("true"), true));
        var rejected = engine.runPreToolHooks(
                HookContext.ofTool(EventName.PRE_TOOL_USE, "WriteFile", Map.of()));
        assertTrue(rejected.isPresent());
        assertTrue(rejected.get().reason().contains("blocked by hook silent-block"),
                rejected.get().reason());
    }

    @Test
    @DisplayName("第一个命中的 reject 短路后续 Hook；非拦截的 pre_tool_use Hook 照样会跑")
    void firstRejectShortCircuits() throws Exception {
        Path marker = tempDir.resolve("second-ran");
        HookEngine engine = new HookEngine();
        engine.register(preHook("first", command("echo 先拦住"), true));
        engine.register(preHook("second",
                command("echo ran > " + marker), false));

        var rejected = engine.runPreToolHooks(
                HookContext.ofTool(EventName.PRE_TOOL_USE, "WriteFile", Map.of()));
        assertTrue(rejected.isPresent());
        assertFalse(Files.exists(marker), "被短路的 Hook 不该执行");

        // 去掉拦截的那条，第二条就该正常跑（通知型 pre_tool_use 也要执行）
        HookEngine engine2 = new HookEngine();
        engine2.register(preHook("second", command("echo ran > " + marker), false));
        assertTrue(engine2.runPreToolHooks(
                HookContext.ofTool(EventName.PRE_TOOL_USE, "WriteFile", Map.of())).isEmpty());
        assertTrue(Files.exists(marker));
    }

    @Test
    @DisplayName("on_error: reject——动作执行失败时当成拦截，用于「校验命令挂了就别干」")
    void onErrorReject() {
        HookEngine engine = new HookEngine();
        Hook h = new Hook("guard", EventName.PRE_TOOL_USE, ConditionGroup.ALWAYS,
                command("exit 7"), false, false, false, "reject");
        engine.register(h);

        var rejected = engine.runPreToolHooks(
                HookContext.ofTool(EventName.PRE_TOOL_USE, "Bash", Map.of()));
        assertTrue(rejected.isPresent(), "失败 + on_error=reject 应当拦下");

        // 同样的失败，on_error 为 ignore 时放行
        HookEngine engine2 = new HookEngine();
        engine2.register(new Hook("guard", EventName.PRE_TOOL_USE, ConditionGroup.ALWAYS,
                command("exit 7"), false, false, false, "ignore"));
        assertTrue(engine2.runPreToolHooks(
                HookContext.ofTool(EventName.PRE_TOOL_USE, "Bash", Map.of())).isEmpty());
    }

    @Test
    @DisplayName("事件不匹配、条件不成立时都不触发")
    void eventAndConditionFiltering() {
        HookEngine engine = new HookEngine();
        engine.register(new Hook("py-only", EventName.POST_TOOL_USE,
                ConditionGroup.parse("tool == \"WriteFile\""), prompt("格式化"), false, false, false, null));

        assertEquals(0, engine.runHooks(HookContext.of(EventName.TURN_START)).size());
        assertEquals(0, engine.runHooks(
                HookContext.ofTool(EventName.POST_TOOL_USE, "ReadFile", Map.of())).size());
        assertEquals(1, engine.runHooks(
                HookContext.ofTool(EventName.POST_TOOL_USE, "WriteFile", Map.of())).size());
    }

    // ───────────────────────── 配置加载 ─────────────────────────

    @Test
    @DisplayName("三处配置追加合并：项目级 → 用户级 → 本地级，顺序即执行顺序")
    void configIsMergedNotFirstWins() throws IOException {
        Path project = tempDir.resolve("project");
        writeConfig(project, HookConfig.PROJECT_PATH, "hooks:\n  - id: from-project\n    event: turn_start\n"
                + "    action:\n      type: prompt\n      message: A\n");
        writeConfig(Path.of(System.getProperty("user.home")), ".agentjava/config.yaml",
                "hooks:\n  - id: from-user\n    event: turn_start\n"
                        + "    action:\n      type: prompt\n      message: B\n");
        writeConfig(project, HookConfig.LOCAL_PATH,
                "hooks:\n  - id: from-local\n    event: turn_start\n"
                        + "    action:\n      type: prompt\n      message: C\n");

        var loaded = HookConfig.load(project.toString());
        assertEquals(3, loaded.hooks().size());
        assertEquals(List.of("from-project", "from-user", "from-local"),
                loaded.hooks().stream().map(Hook::id).toList());
        assertEquals(List.of("项目级", "用户级", "本地级"),
                loaded.hooks().stream().map(Hook::source).toList());
        assertTrue(loaded.errors().isEmpty());
    }

    @Test
    @DisplayName("实战篇那条 block-json-write 原样加载：条件、动作、reject 都在")
    void courseSampleConfigLoads() throws IOException {
        Path project = tempDir.resolve("project");
        writeConfig(project, HookConfig.PROJECT_PATH, """
                hooks:
                  - id: block-json-write
                    event: pre_tool_use
                    if: 'tool == "WriteFile" && args.file_path =~ /\\.json$/'
                    action:
                      type: command
                      command: 'echo "禁止直接写入 JSON 文件，请使用专用工具"'
                    reject: true
                """);
        var loaded = HookConfig.load(project.toString());
        assertEquals(1, loaded.hooks().size());
        Hook h = loaded.hooks().get(0);
        assertEquals(EventName.PRE_TOOL_USE, h.event());
        assertTrue(h.reject());
        assertTrue(HookEngine.validate(loaded.hooks()).isEmpty());
        assertTrue(h.matches(HookContext.ofTool(EventName.PRE_TOOL_USE, "WriteFile",
                Map.of("file_path", "/tmp/a/config.json"))));
        assertFalse(h.matches(HookContext.ofTool(EventName.PRE_TOOL_USE, "WriteFile",
                Map.of("file_path", "/tmp/a/config.yaml"))));
    }

    @Test
    @DisplayName("timeout 支持 30（秒）、500ms、1m 三种写法")
    void timeoutParsing() throws IOException {
        Path project = tempDir.resolve("project");
        writeConfig(project, HookConfig.PROJECT_PATH, """
                hooks:
                  - id: a
                    event: post_tool_use
                    action:
                      type: command
                      command: echo a
                      timeout: 30
                  - id: b
                    event: post_tool_use
                    action:
                      type: command
                      command: echo b
                      timeout: 500ms
                  - id: c
                    event: post_tool_use
                    action:
                      type: command
                      command: echo c
                      timeout: 1m
                """);
        var loaded = HookConfig.load(project.toString());
        assertEquals(Duration.ofSeconds(30), loaded.hooks().get(0).action().timeout());
        assertEquals(Duration.ofMillis(500), loaded.hooks().get(1).action().timeout());
        assertEquals(Duration.ofMinutes(1), loaded.hooks().get(2).action().timeout());
    }

    @Test
    @DisplayName("坏规则单独丢弃并给出定位，好规则照常加载")
    void badHookIsSkippedWithLocation() throws IOException {
        Path project = tempDir.resolve("project");
        writeConfig(project, HookConfig.PROJECT_PATH, """
                hooks:
                  - id: good
                    event: post_tool_use
                    action:
                      type: prompt
                      message: ok
                  - id: bad-event
                    event: no_such_event
                    action:
                      type: prompt
                      message: nope
                  - id: bad-condition
                    event: post_tool_use
                    if: 'tool == "A" && args.x == "1" || args.y == "2"'
                    action:
                      type: prompt
                      message: nope
                """);
        var loaded = HookConfig.load(project.toString());
        assertEquals(1, loaded.hooks().size());
        assertEquals("good", loaded.hooks().get(0).id());
        assertEquals(2, loaded.errors().size());
        assertTrue(loaded.errors().get(0).contains("no_such_event"), loaded.errors().get(0));
        assertTrue(loaded.errors().get(1).contains("不能混用"), loaded.errors().get(1));
    }

    @Test
    @DisplayName("三处配置文件都不存在时安静返回空，不报错")
    void missingConfigIsFine() {
        var loaded = HookConfig.load(tempDir.resolve("empty").toString());
        assertTrue(loaded.hooks().isEmpty());
        assertTrue(loaded.errors().isEmpty());
    }

    // ───────────────────────── 配置校验 ─────────────────────────

    @Test
    @DisplayName("reject 只能用在 pre_tool_use 上")
    void validateRejectPlacement() {
        Hook h = new Hook("x", EventName.POST_TOOL_USE, ConditionGroup.ALWAYS,
                prompt("nope"), true, false, false, null);
        List<String> errors = HookEngine.validate(h, "hooks[0]");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("reject 只能用在 pre_tool_use"), errors.get(0));
        assertTrue(errors.get(0).contains("hooks[0]"), errors.get(0));
        assertTrue(errors.get(0).contains("x"), "错误信息要能定位到具体那条 Hook");
    }

    @Test
    @DisplayName("async 不能用在 pre_tool_use 上")
    void validateAsyncPlacement() {
        Hook h = new Hook("x", EventName.PRE_TOOL_USE, ConditionGroup.ALWAYS,
                command("true"), false, false, true, null);
        List<String> errors = HookEngine.validate(h, "hooks[1]");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("不支持 async"), errors.get(0));
    }

    @Test
    @DisplayName("每种动作类型的必填字段分别点名")
    void validateRequiredFields() {
        assertTrue(HookEngine.validate(new Hook("a", EventName.TURN_START, ConditionGroup.ALWAYS,
                new Action(ActionType.COMMAND, null, null, null, null, null, null, null),
                false, false, false, null), "hooks[0]").get(0).contains("action.command"));
        assertTrue(HookEngine.validate(new Hook("b", EventName.TURN_START, ConditionGroup.ALWAYS,
                new Action(ActionType.PROMPT, null, null, null, null, null, null, null),
                false, false, false, null), "hooks[0]").get(0).contains("action.message"));
        assertTrue(HookEngine.validate(new Hook("c", EventName.TURN_START, ConditionGroup.ALWAYS,
                new Action(ActionType.HTTP, null, null, null, null, null, null, null),
                false, false, false, null), "hooks[0]").get(0).contains("action.url"));
        assertTrue(HookEngine.validate(new Hook("d", EventName.TURN_START, ConditionGroup.ALWAYS,
                new Action(ActionType.AGENT, null, null, null, null, null, null, null),
                false, false, false, null), "hooks[0]").get(0).contains("action.message"));
    }

    @Test
    @DisplayName("http 的 url 必须是 http/https 且带 host；on_error 只能取 ignore/reject")
    void validateUrlAndOnError() {
        assertTrue(HookEngine.validate(new Hook("u", EventName.TURN_START, ConditionGroup.ALWAYS,
                http("ftp://x/y"), false, false, false, null), "hooks[0]").get(0).contains("url 不合法"));
        assertTrue(HookEngine.validate(new Hook("e", EventName.PRE_TOOL_USE, ConditionGroup.ALWAYS,
                command("true"), false, false, false, "explode"), "hooks[0]").get(0).contains("on_error"));
        assertTrue(HookEngine.validate(new Hook("ok", EventName.PRE_TOOL_USE, ConditionGroup.ALWAYS,
                command("true"), false, false, false, "reject"), "hooks[0]").isEmpty());
    }

    @Test
    @DisplayName("校验聚合全部错误，不会因为第一条坏就停")
    void validateAggregates() {
        List<Hook> hooks = List.of(
                new Hook("a", EventName.TURN_START, ConditionGroup.ALWAYS, command(null), false, false, false, null),
                new Hook("b", EventName.POST_TOOL_USE, ConditionGroup.ALWAYS, prompt("m"), true, false, false, null));
        List<String> errors = HookEngine.validate(hooks);
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).contains("hooks[0]"));
        assertTrue(errors.get(1).contains("hooks[1]"));
    }

    // ───────────────────────── 与工具执行链路的集成 ─────────────────────────

    @Test
    @DisplayName("实战篇那条规则：WriteFile 写 .json 被拦下，文件没落盘，拒绝原因回灌给 LLM")
    void preToolUseRejectsWriteFileEndToEnd() throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Path target = project.resolve("config.json");

        HookEngine engine = new HookEngine();
        engine.setWorkDir(project.toString());
        engine.register(new Hook("block-json-write", EventName.PRE_TOOL_USE,
                ConditionGroup.parse("tool == \"WriteFile\" && args.file_path =~ /\\.json$/"),
                command("echo \"禁止直接写入 JSON 文件，请使用专用工具\""),
                true, false, false, null, "项目级"));

        var queue = new LinkedBlockingQueue<AgentEvent>();
        var checker = new PermissionChecker(PermissionMode.BYPASS, project);
        var executor = new StreamingExecutor(ToolRegistry.createDefault(), queue, checker, engine);

        var results = executor.executeAll(List.of(new StreamingExecutor.ToolCallInfo(
                "t1", "WriteFile", Map.of("file_path", target.toString(), "content", "{}"))));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isError(), "被 Hook 拦下的调用要当成错误结果");
        assertTrue(results.get(0).output().contains("Rejected by hook"), results.get(0).output());
        assertTrue(results.get(0).output().contains("禁止直接写入 JSON 文件"), results.get(0).output());
        assertFalse(Files.exists(target), "工具不该真的执行");
    }

    @Test
    @DisplayName("不满足条件的工具调用正常放行")
    void nonMatchingToolCallPassesThrough() {
        HookEngine engine = new HookEngine();
        engine.register(new Hook("block-json-write", EventName.PRE_TOOL_USE,
                ConditionGroup.parse("tool == \"WriteFile\" && args.file_path =~ /\\.json$/"),
                command("echo nope"), true, false, false, null));

        var rejected = engine.runPreToolHooks(HookContext.ofTool(
                EventName.PRE_TOOL_USE, "WriteFile", Map.of("file_path", "/tmp/a/config.yaml")));
        assertTrue(rejected.isEmpty(), "写 .yaml 不该被拦");
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private static boolean eval(String expression, HookContext ctx) {
        return ConditionGroup.parse(expression).matches(ctx);
    }

    private static HookContext ctx(String toolName, Map<String, Object> args) {
        return new HookContext(EventName.PRE_TOOL_USE, toolName, args, null, null, null);
    }

    private static Hook preHook(String id, Action action, boolean reject) {
        return new Hook(id, EventName.PRE_TOOL_USE, ConditionGroup.ALWAYS, action,
                reject, false, false, null);
    }

    /** 通知型事件上的 Hook：post_tool_use 走 runHooks，不参与拦截。 */
    private static Hook notifyHook(String id, Action action) {
        return new Hook(id, EventName.POST_TOOL_USE, ConditionGroup.ALWAYS, action,
                false, false, false, null);
    }

    private static Action command(String cmd) {
        return new Action(ActionType.COMMAND, cmd, null, null, null, null, null, null);
    }

    private static Action prompt(String message) {
        return new Action(ActionType.PROMPT, null, message, null, null, null, null, null);
    }

    private static Action http(String url) {
        return new Action(ActionType.HTTP, null, null, url, null, null, null, null);
    }

    private static Action agent(String message) {
        return new Action(ActionType.AGENT, null, message, null, null, null, null, null);
    }

    private static void writeConfig(Path base, String relative, String yaml) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
    }
}
