package com.agentjava.hook;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Hook 引擎：持有全部规则，在生命周期节点上求值条件、执行动作。
 *
 * <p>两条执行路径：
 * <ul>
 *   <li>{@link #runHooks(HookContext)}：所有非拦截事件走这里，fire-and-forget，
 *       跑完就完了，不影响主流程；</li>
 *   <li>{@link #runPreToolHooks(HookContext)}：{@code pre_tool_use} 专用，
 *       返回 {@link ToolRejectedError} 表示「这个工具不许调」。</li>
 * </ul>
 *
 * <p>三条贯穿全局的设计原则：
 * <ol>
 *   <li><b>错误不外溢</b>：Hook 是辅助机制，格式化失败、通知没发出去都不该打断 Agent。
 *       动作执行出错只记日志，返回一条失败结果——「尾巴摇狗」是要避免的；</li>
 *   <li><b>声明顺序即执行顺序</b>：多个 Hook 命中同一事件时按 YAML 里的先后执行，
 *       {@code pre_tool_use} 上第一个 reject 立刻短路，后面的不再跑。
 *       所以兜底拦截规则要写在前面；</li>
 *   <li><b>异步只对非拦截事件开放</b>：{@code pre_tool_use} 必须同步拿到结果才能决定放行，
 *       {@code async} 在这个事件上没有意义，配置校验阶段就把它挡掉。</li>
 * </ol>
 *
 * <p>整个 Hook 系统只依赖标准库（{@code java.net.http}、{@code java.util.regex}、
 * {@code java.nio.file}），不引入外部框架。
 */
public class HookEngine {

    /** command 默认超时：跑测试、跑格式化都可能要一会儿，给足 10 分钟。 */
    public static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMinutes(10);

    /** http 默认超时：通知类请求，10 秒足够，超时就放弃。 */
    public static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(10);

    /** HTTP 响应体截断阈值，防止一个巨大响应把内存撑爆。 */
    public static final int MAX_HTTP_BODY_CHARS = 64 * 1024;

    /** 只登记一次错误，避免同一条坏 Hook 每轮刷一行日志。 */
    private final List<Hook> hooks = new ArrayList<>();

    /** 执行结果队列：TUI 每轮结束取走一次，取走即清空。async 线程会并发写，用同步容器。 */
    private final List<HookResult> notifications = Collections.synchronizedList(new ArrayList<>());

    /**
     * agent 类型动作的执行回调，由上层注入。
     *
     * <p>用回调而不是直接依赖 Agent 模块，是为了让 Hook 包保持零外部依赖。
     * 未注册时执行器返回一句明确说明，而不是 NPE——真实运行时交给 SubAgent。
     */
    private BiFunction<String, HookContext, String> agentRunner;

    /** 日志出口：默认丢弃。TUI 层可以接进去，把 Hook 的异常写进界面。 */
    private Consumer<String> logger = msg -> {
    };

    /** command 执行的工作目录；null 时继承 JVM 的工作目录。 */
    private String workDir;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(DEFAULT_HTTP_TIMEOUT)
            .build();

    // ───────────────────────── 注册 ─────────────────────────

    public synchronized void register(Hook hook) {
        if (hook != null) {
            hooks.add(hook);
        }
    }

    public synchronized void registerAll(Collection<Hook> list) {
        if (list == null) {
            return;
        }
        for (Hook h : list) {
            register(h);
        }
    }

    /** 快照：遍历期间列表被改动也不会炸（注册是 synchronized 的，遍历不握锁）。 */
    public synchronized List<Hook> hooks() {
        return new ArrayList<>(hooks);
    }

    public synchronized int size() {
        return hooks.size();
    }

    public synchronized void clear() {
        hooks.clear();
    }

    /** 重置全部 once 标记：开新会话时调用。 */
    public synchronized void resetExecuted() {
        for (Hook h : hooks) {
            h.reset();
        }
    }

    public void setAgentRunner(BiFunction<String, HookContext, String> runner) {
        this.agentRunner = runner;
    }

    public void setLogger(Consumer<String> logger) {
        if (logger != null) {
            this.logger = logger;
        }
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    private synchronized List<Hook> snapshotHooks() {
        return new ArrayList<>(hooks);
    }

    // ───────────────────────── 触发 ─────────────────────────

    /**
     * 触发一个非拦截事件：事件名过滤 → 条件匹配 → once 去重 → 执行动作。
     *
     * <p>async 的 Hook 丢进后台线程，主流程拿一个 {@code "(async)"} 占位结果——
     * 调用方知道它触发过就够了，真正的输出等它跑完进 notifications 队列。
     */
    public List<HookResult> runHooks(HookContext ctx) {
        List<HookResult> results = new ArrayList<>();
        if (ctx == null || ctx.event() == null) {
            return results;
        }
        for (Hook h : snapshotHooks()) {
            if (h.event() != ctx.event()) {
                continue;
            }
            if (!h.matches(ctx) || !h.shouldRun()) {
                continue;
            }
            h.markExecuted();
            if (h.async()) {
                CompletableFuture.runAsync(() -> notifications.add(executeAction(h, ctx)));
                results.add(new HookResult(h.label(), ctx.event(),
                        actionTypeOf(h), "(async)", true, false));
                continue;
            }
            HookResult result = executeAction(h, ctx);
            results.add(result);
            notifications.add(result);
        }
        return results;
    }

    /**
     * 触发 {@code pre_tool_use}——全流程唯一能阻断执行的入口。
     *
     * <p>和 {@link #runHooks} 的关键差别：命中的 Hook 全都执行（非 reject 的也要跑，
     * 它们可能是通知、审计这类副作用），但遇到第一个决定拦截的立刻返回，后续 Hook 不再执行。
     *
     * <p>拦截有两条来源：配置里显式 {@code reject: true}；动作执行失败且
     * {@code on_error: reject}。后者是个降级策略——比如「调审批 API，API 挂了就当拒绝」。
     *
     * <p>这里不支持 async：必须同步等结果才能决定放行。
     */
    public Optional<ToolRejectedError> runPreToolHooks(HookContext ctx) {
        if (ctx == null) {
            return Optional.empty();
        }
        for (Hook h : snapshotHooks()) {
            if (h.event() != EventName.PRE_TOOL_USE) {
                continue;
            }
            if (!h.matches(ctx) || !h.shouldRun()) {
                continue;
            }
            h.markExecuted();
            HookResult result = executeAction(h, ctx);
            notifications.add(result);

            boolean reject = h.reject()
                    || (!result.success() && "reject".equalsIgnoreCase(h.onError()));
            if (reject) {
                String reason = result.output();
                if (reason == null || reason.isBlank()) {
                    reason = "blocked by hook " + h.label();
                }
                return Optional.of(
                        new ToolRejectedError(ctx.toolName(), reason, h.id()));
            }
        }
        return Optional.empty();
    }

    /** 取走全部通知，取走即清空。 */
    public List<HookResult> drainNotifications() {
        synchronized (notifications) {
            List<HookResult> copy = List.copyOf(notifications);
            notifications.clear();
            return copy;
        }
    }

    // ───────────────────────── 四种动作执行器 ─────────────────────────

    private HookResult executeAction(Hook h, HookContext ctx) {
        Action a = h.action();
        if (a == null || a.type() == null) {
            return fail(h, ctx, null, "hook 没有配置 action 或 action.type");
        }
        try {
            return switch (a.type()) {
                case COMMAND -> executeCommand(h, ctx, a);
                case PROMPT -> new HookResult(h.label(), ctx.event(), ActionType.PROMPT,
                        nz(ctx.expand(a.message())), true, h.reject());
                case HTTP -> executeHttp(h, ctx, a);
                case AGENT -> executeAgent(h, ctx, a);
            };
        } catch (Exception e) {
            // 错误兜底：只记日志，不往上抛——辅助机制的故障不能反过来搞崩主流程
            log("Hook " + h.label() + " 执行失败：" + e.getMessage());
            return fail(h, ctx, a.type(), "hook error: " + e.getMessage());
        }
    }

    /**
     * command：模板替换 → {@code bash -c} → 等超时 → 收输出。
     *
     * <p>环境变量注入三个（{@code AGENTJAVA_EVENT} / {@code AGENTJAVA_TOOL} /
     * {@code AGENTJAVA_FILE_PATH}），让不便于用 {@code $VAR} 占位的地方也能拿到上下文。
     * 返回码 0 视为成功，非 0 视为失败；超时的返回失败结果，配合
     * {@code on_error: reject} 就能做出「校验命令超时即拦截」这类规则。
     */
    private HookResult executeCommand(Hook h, HookContext ctx, Action a) throws Exception {
        String command = ctx.expand(a.command());
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.redirectErrorStream(true);
        if (workDir != null && !workDir.isBlank()) {
            pb.directory(new File(workDir));
        }
        Map<String, String> env = pb.environment();
        env.put("AGENTJAVA_EVENT", ctx.event() == null ? "" : ctx.event().value());
        env.put("AGENTJAVA_TOOL", nz(ctx.toolName()));
        env.put("AGENTJAVA_FILE_PATH", nz(ctx.filePath()));

        Process proc = pb.start();
        Duration timeout = a.timeoutOr(DEFAULT_COMMAND_TIMEOUT);
        boolean finished = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            proc.destroyForcibly();
            return fail(h, ctx, ActionType.COMMAND, "command timed out after " + timeout.toMillis() + "ms");
        }
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int code = proc.exitValue();
        return new HookResult(h.label(), ctx.event(), ActionType.COMMAND, output, code == 0, h.reject());
    }

    /**
     * http：默认 POST，默认超时 10 秒；没配 body 时把上下文自动序列化成 JSON 发过去。
     *
     * <p>响应体超过 64KB 截断。请求头的值也走模板替换，方便把 token 之类的动态值写进去。
     */
    private HookResult executeHttp(Hook h, HookContext ctx, Action a) throws Exception {
        String url = ctx.expand(a.url());
        if (url == null || url.isBlank()) {
            return fail(h, ctx, ActionType.HTTP, "http action 缺少 url");
        }
        String body = a.body();
        body = (body == null || body.isBlank()) ? defaultJsonPayload(ctx) : ctx.expand(body);
        String method = (a.method() == null || a.method().isBlank())
                ? "POST" : a.method().trim().toUpperCase(Locale.ROOT);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(a.timeoutOr(DEFAULT_HTTP_TIMEOUT))
                .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));

        boolean hasContentType = false;
        Map<String, String> headers = a.headers();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String key = e.getKey();
                String value = nz(ctx.expand(e.getValue()));
                builder.header(key, value);
                if ("Content-Type".equalsIgnoreCase(key)) {
                    hasContentType = true;
                }
            }
        }
        if (!hasContentType && body != null && !body.isBlank()) {
            builder.header("Content-Type", "application/json");
        }

        HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String out = response.body() == null ? "" : response.body();
        if (out.length() > MAX_HTTP_BODY_CHARS) {
            out = out.substring(0, MAX_HTTP_BODY_CHARS) + "\n...(truncated)";
        }
        int status = response.statusCode();
        return new HookResult(h.label(), ctx.event(), ActionType.HTTP, out,
                status >= 200 && status < 300, h.reject());
    }

    /**
     * agent：这里只搭骨架。没注册 {@code agentRunner} 时返回一句明确的说明，
     * 而不是抛异常——配置能加载、能校验通过，真实子 Agent 运行时由 SubAgent 对接。
     */
    private HookResult executeAgent(Hook h, HookContext ctx, Action a) {
        if (agentRunner == null) {
            return new HookResult(h.label(), ctx.event(), ActionType.AGENT,
                    "agent 类型 Hook 已配置，但还没有注册 AgentRunner（SubAgent 对接后生效）",
                    false, h.reject());
        }
        String prompt = a.message();
        if (prompt == null || prompt.isBlank()) {
            prompt = a.command();
        }
        prompt = ctx.expand(prompt);
        String output = agentRunner.apply(prompt == null ? "" : prompt, ctx);
        return new HookResult(h.label(), ctx.event(), ActionType.AGENT,
                output == null ? "" : output, true, h.reject());
    }

    /** 没配 body 时的默认 JSON：把上下文里最常见的几个字段带上。 */
    private static String defaultJsonPayload(HookContext ctx) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"event\":\"").append(json(ctx.event() == null ? "" : ctx.event().value())).append('"');
        sb.append(",\"tool\":\"").append(json(nz(ctx.toolName()))).append('"');
        sb.append(",\"file_path\":\"").append(json(nz(ctx.filePath()))).append('"');
        sb.append(",\"message\":\"").append(json(nz(ctx.message()))).append('"');
        sb.append(",\"error\":\"").append(json(nz(ctx.error()))).append('"');
        return sb.append('}').toString();
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ───────────────────────── 配置校验 ─────────────────────────

    /**
     * 校验一批 Hook，聚合全部错误一次性返回——第一个错误就中断会让用户改完一个再见到下一个。
     *
     * <p>校验项：事件名合法、action 存在且类型合法、每类动作的必填字段、
     * {@code reject} 只在 {@code pre_tool_use}、{@code async} 不能用在 {@code pre_tool_use}、
     * timeout 非负、{@code on_error} 取值合法。
     */
    public static List<String> validate(Collection<Hook> hooks) {
        List<String> errors = new ArrayList<>();
        if (hooks == null) {
            return errors;
        }
        int i = 0;
        for (Hook h : hooks) {
            errors.addAll(validate(h, "hooks[" + i + "]"));
            i++;
        }
        return errors;
    }

    /**
     * 校验单条 Hook。
     *
     * @param where 定位串，形如 {@code hooks[2]}；错误信息里会带上 id，方便在 YAML 里找
     */
    public static List<String> validate(Hook h, String where) {
        List<String> errors = new ArrayList<>();
        if (h == null) {
            errors.add(where + ": 不是一条合法的 Hook（解析失败）");
            return errors;
        }
        String at = where + "（" + h.label() + "）";

        if (h.event() == null) {
            errors.add(at + ": 缺少 event，可选值为 " + EventName.allValues());
        }
        Action action = h.action();
        if (action == null) {
            errors.add(at + ": 缺少 action");
            return errors;
        }
        if (action.type() == null) {
            errors.add(at + ": 缺少 action.type，可选值为 command / prompt / http / agent");
            return errors;
        }

        if (h.reject() && h.event() != EventName.PRE_TOOL_USE) {
            errors.add(at + ": reject 只能用在 pre_tool_use 上（"
                    + (h.event() == null ? "未指定事件" : h.event().value())
                    + " 时工具已经跑完了，拒绝没有意义）");
        }
        if (h.async() && h.event() == EventName.PRE_TOOL_USE) {
            errors.add(at + ": pre_tool_use 不支持 async（必须同步拿到结果才能决定是否拦截）");
        }
        if (action.timeout() != null && action.timeout().isNegative()) {
            errors.add(at + ": timeout 不能为负数");
        }
        String onError = h.onError();
        if (onError != null && !onError.isBlank()
                && !"ignore".equalsIgnoreCase(onError) && !"reject".equalsIgnoreCase(onError)) {
            errors.add(at + ": on_error 只能取 ignore 或 reject，当前为 '" + onError + "'");
        }

        switch (action.type()) {
            case COMMAND -> {
                if (isBlank(action.command())) {
                    errors.add(at + ": command 类型必须填 action.command");
                }
            }
            case PROMPT -> {
                if (isBlank(action.message())) {
                    errors.add(at + ": prompt 类型必须填 action.message");
                }
            }
            case HTTP -> {
                String url = action.url();
                if (isBlank(url)) {
                    errors.add(at + ": http 类型必须填 action.url");
                } else if (!isValidHttpUrl(url)) {
                    errors.add(at + ": action.url 不合法（必须是 http/https 且带 host）：" + url);
                }
            }
            case AGENT -> {
                if (isBlank(action.message()) && isBlank(action.command())) {
                    errors.add(at + ": agent 类型必须填 action.message（或 action.command 作为 fallback）");
                }
            }
        }
        return errors;
    }

    private static boolean isValidHttpUrl(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            return uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private static ActionType actionTypeOf(Hook h) {
        return h.action() == null ? null : h.action().type();
    }

    private static HookResult fail(Hook h, HookContext ctx, ActionType type, String message) {
        return new HookResult(h.label(), ctx == null ? null : ctx.event(), type, message, false, h.reject());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void log(String message) {
        try {
            logger.accept(message);
        } catch (RuntimeException ignored) {
            // 日志出口本身出问题也不能影响 Hook 流程
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 关掉 HTTP 连接池（程序退出时调用；不调用也无妨，HttpClient 会随 JVM 回收）。 */
    public void shutdown() {
        httpClient.close();
    }
}
