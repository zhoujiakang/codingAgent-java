package com.agentjava.tui;

import com.agentjava.agent.Agent;
import com.agentjava.agent.AgentEvent;
import com.agentjava.compact.ContextCompactor;
import com.agentjava.compact.RecoveryState;
import com.agentjava.config.ProviderConfig;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.llm.LlmClient;
import com.agentjava.mcp.McpManager;
import com.agentjava.mcp.McpServerConfig;
import com.agentjava.permission.PermissionMode;
import com.agentjava.permission.PermissionResponse;
import com.agentjava.prompt.PlanModePrompt;
import com.agentjava.prompt.PromptBuilder;
import com.agentjava.tool.ToolRegistry;
import com.agentjava.tui.tea.Ansi;
import com.agentjava.tui.tea.Command;
import com.agentjava.tui.tea.KeyPressMessage;
import com.agentjava.tui.tea.Message;
import com.agentjava.tui.tea.Model;
import com.agentjava.tui.tea.Program;
import com.agentjava.tui.tea.UpdateResult;
import com.agentjava.tui.tea.WindowSizeMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;

/**
 * 主 TUI 模型。
 *
 * 真正复杂的"调 LLM → 执行工具 → 喂回结果"主循环搬到了 {@link Agent} 里。
 * 这个模型只负责三件事：把用户输入交给 Agent、把 Agent 吐出的 {@link AgentEvent}
 * 渲染成界面、处理本地输入命令。Agent 跑在虚拟线程上，事件通过队列回投到主线程，
 * 所以这里的 update() 始终是纯粹的状态变换，不碰网络、不碰 IO。
 *
 * 一轮对话的节奏：用户输入 → 进历史 → Agent 循环多轮直到完成 → 事件流实时渲染。
 */
public class AgentModel implements Model {

    public static final String APP_NAME = "AgentJava";
    public static final String VERSION = "0.1.0";

    /** 退出命令。 */
    public static final String EXIT_COMMAND = "/exit";

    private final List<ProviderConfig> providers;
    private final String workingDir;
    private String systemPrompt;
    private final ConversationManager conversation = new ConversationManager();

    /** 工具注册中心：六个内置工具 + 共享的文件状态缓存 + 启动期接入的 MCP 工具。 */
    private final ToolRegistry registry = ToolRegistry.createDefault();

    /** MCP 连接管理器；未配置 MCP Server 时为 null。 */
    private McpManager mcpManager;

    /** 启动期 MCP 接入状态，打印在 banner 之后给用户一个明确反馈。 */
    private String mcpStatus = "";

    private Program program;
    private AppState state;
    private int selectedIndex = 0;
    private ProviderConfig activeProvider;
    private LlmClient llmClient;

    /** Plan Mode 开关：开启后 Agent 只暴露只读工具，并注入"只规划不改动"的指引。 */
    private boolean planMode = false;

    /** 权限模式：决定只读/写/命令各自的默认处置（见 PermissionMode 矩阵）。 */
    private PermissionMode permissionMode = PermissionMode.DEFAULT;

    /** 是否正在等待用户对一个权限请求的拍板（确认期间拦截普通输入）。 */
    private boolean awaitingPermission = false;

    /** 当前待决的权限请求 future；用户按键后由 UI 回传 PermissionResponse。 */
    private CompletableFuture<PermissionResponse> pendingPermission;

    /** 会话 ID：隔离各会话的工具结果溢写目录。 */
    private final String sessionId = UUID.randomUUID().toString();

    /** 上下文窗口（token）：从 provider 配置读取，驱动压缩阈值。 */
    private int contextWindow = 200_000;

    /** 跨轮次累积的恢复状态（最近访问的文件 / 激活的 Skill），随会话持久。 */
    private final RecoveryState recovery = new RecoveryState();

    /** 自动压缩熔断器，跨轮次累积。 */
    private final ContextCompactor.AutoCompactTrackingState breaker =
            new ContextCompactor.AutoCompactTrackingState();

    /** 本轮是否因错误收场，用于在 LoopComplete 时避免重复落地。 */
    private boolean agentErrored = false;

    /** 输入缓冲：Alt+Enter / Ctrl+J 插入换行，Enter 提交。 */
    private final StringBuilder input = new StringBuilder();

    /** 流式缓冲：片段实时进这里，本轮结束才落成一条历史消息（由 Agent 负责落地）。 */
    private final StringBuilder streamBuf = new StringBuilder();
    private boolean streaming = false;
    private long requestStartMillis = 0;
    private String spinnerVerb = SpinnerVerbs.random();

    /** 上一轮的统计信息，用于状态栏展示。 */
    private long lastElapsedMillis = 0;
    private int lastInputTokens = 0;
    private int lastOutputTokens = 0;
    private boolean usageAvailable = false;

    private int width = 80;
    private int height = 24;
    private boolean bannerPrinted = false;

    public AgentModel(List<ProviderConfig> providers, String workingDir) {
        this(providers, workingDir, List.of());
    }

    public AgentModel(List<ProviderConfig> providers, String workingDir, List<McpServerConfig> mcpConfigs) {
        this.providers = List.copyOf(providers);
        this.workingDir = workingDir == null ? System.getProperty("user.dir") : workingDir;
        // 先用环境探测（此时 provider 未激活，model 未知）构建一次七模块 System Prompt；
        // 激活 provider 拿到 model 后会用真实 model 重建，让 Environment 段落带上模型名。
        this.systemPrompt = PromptBuilder.buildSystemPrompt(
                PromptBuilder.detectEnvironment(null), null);
        // 启动期接入 MCP：连接配置的 Server、发现工具、注册进 ToolRegistry。
        // 单个 Server 失败只收集错误，不阻断其余 Server，也不阻止启动。
        if (mcpConfigs != null && !mcpConfigs.isEmpty()) {
            try {
                this.mcpManager = new McpManager(mcpConfigs);
                var result = mcpManager.registerAllTools(registry);
                if (!result.errors().isEmpty()) {
                    mcpStatus = Styles.ERROR.render("⚠ MCP 接入异常: " + String.join("; ", result.errors()));
                } else if (result.serverCount() > 0) {
                    mcpStatus = Styles.ACCENT.render("✻ 已连接 " + result.serverCount() + " 个 MCP Server，注册 "
                            + result.tools().size() + " 个工具");
                }
                // 退出时统一关闭 MCP 连接，释放子进程与传输资源
                final McpManager mgr = mcpManager;
                Runtime.getRuntime().addShutdownHook(new Thread(mgr::shutdown));
            } catch (Exception e) {
                mcpStatus = Styles.ERROR.render("⚠ MCP 初始化失败: " + e.getMessage());
            }
        }
        // 只有一个 provider 时直接进入对话，多个时才让用户选（F2）
        if (this.providers.size() == 1) {
            this.state = AppState.CHAT;
            activateProvider(0);
        } else {
            this.state = AppState.PROVIDER_SELECT;
        }
    }

    /** Program 建好之后回灌进来，Agent 的事件要靠它投回事件循环。 */
    public void setProgram(Program program) {
        this.program = program;
    }

    // ───────────────────────── Model 接口实现 ─────────────────────────

    @Override
    public Command init() {
        return Command.none();
    }

    @Override
    public UpdateResult<? extends Model> update(Message msg) {
        if (msg instanceof WindowSizeMessage ws) {
            width = ws.width();
            height = ws.height();
            if (!bannerPrinted) {
                bannerPrinted = true;
                StringBuilder sb = new StringBuilder(renderBanner()).append("\n").append(renderReadyLine());
                if (!mcpStatus.isEmpty()) {
                    sb.append("\n").append(mcpStatus);
                }
                return UpdateResult.of(this, Command.println(sb.toString()));
            }
            return UpdateResult.of(this);
        }
        if (msg instanceof KeyPressMessage key) {
            return UpdateResult.of(this, handleKey(key));
        }
        if (msg instanceof AgentEventMessage aem) {
            return UpdateResult.of(this, handleAgentEvent(aem.event()));
        }
        return UpdateResult.of(this);
    }

    @Override
    public String view() {
        return state == AppState.PROVIDER_SELECT ? viewProviderSelect() : viewChat();
    }

    // ───────────────────────── 事件处理 ─────────────────────────

    private Command handleKey(KeyPressMessage key) {
        if ("ctrl+c".equals(key.key()) || "ctrl+d".equals(key.key())) {
            return Command.quit();
        }
        if (state == AppState.PROVIDER_SELECT) {
            return handleProviderSelectKey(key);
        }
        return handleChatKey(key);
    }

    private Command handleProviderSelectKey(KeyPressMessage key) {
        switch (key.key()) {
            case "up" -> selectedIndex = Math.max(0, selectedIndex - 1);
            case "down" -> selectedIndex = Math.min(providers.size() - 1, selectedIndex + 1);
            case "enter" -> {
                activateProvider(selectedIndex);
                state = AppState.CHAT;
            }
            default -> {
            }
        }
        return Command.none();
    }

    private Command handleChatKey(KeyPressMessage key) {
        // 权限确认弹窗优先于普通输入：此时 Agent Loop 阻塞在 future 上，等待用户拍板
        if (awaitingPermission) {
            return handlePermissionKey(key);
        }
        if (streaming) {
            return Command.none();
        }
        switch (key.key()) {
            case "enter" -> {
                return submit();
            }
            // 多行编辑：Alt+Enter 与 Ctrl+J 都支持，不同终端发的序列不一样
            case "alt+enter", "ctrl+j" -> input.append('\n');
            case "backspace" -> {
                if (input.length() > 0) {
                    input.deleteCharAt(input.length() - 1);
                }
            }
            case "delete" -> input.setLength(0);
            case "ctrl+u" -> input.setLength(0);
            case "rune" -> input.append(key.rune());
            default -> {
            }
        }
        return Command.none();
    }

    /** 提交一轮对话，或切换 Plan Mode。 */
    private Command submit() {
        String text = input.toString();
        input.setLength(0);
        if (text.isBlank()) {
            return Command.none();
        }
        String trimmed = text.trim();
        if (EXIT_COMMAND.equals(trimmed)) {
            return Command.quit();
        }
        // 手动压缩：跳过阈值与熔断器，直接生成结构化摘要重建对话；报告压缩前后估算 token 变化
        if ("/compact".equals(trimmed)) {
            if (llmClient == null) {
                return Command.println(Styles.ERROR.render("✗ 还没有可用的 provider。"));
            }
            int before = ContextCompactor.estimateTokens(conversation.getMessages());
            List<Map<String, Object>> schemas =
                    registry.getAllSchemas(activeProvider.normalizedProtocol());
            ContextCompactor.forceCompact(
                    conversation, llmClient, contextWindow, workingDir, sessionId, recovery, schemas);
            int after = ContextCompactor.estimateTokens(conversation.getMessages());
            return Command.println(Styles.ACCENT.render(
                    "✻ 手动压缩完成: " + before + " → " + after + " estimated tokens"));
        }
        // 切换权限模式：/mode default|accept|plan|yolo
        if (trimmed.startsWith("/mode")) {
            return handleModeCommand(trimmed.substring(5).trim());
        }
        // 切换 Plan Mode：/plan 只读只规划，/do 恢复正常工作模式
        if ("/plan".equals(trimmed)) {
            planMode = true;
            permissionMode = PermissionMode.PLAN;
            String planPath = PlanModePrompt.getPlanPath(workingDir);
            conversation.addSystemReminder(
                    PlanModePrompt.buildReentryReminder(planPath));
            return Command.println(Styles.ACCENT.render("✻ 已进入 Plan Mode（只读，只规划不改动）"));
        }
        if ("/do".equals(trimmed)) {
            planMode = false;
            permissionMode = PermissionMode.DEFAULT;
            String planPath = PlanModePrompt.getPlanPath(workingDir);
            conversation.addSystemReminder(
                    PlanModePrompt.buildExitReminder(planPath, PlanModePrompt.planExists(planPath)));
            return Command.println(Styles.ACCENT.render("✻ 已退出 Plan Mode，恢复正常工作模式"));
        }

        // 用户输入先写进 scrollback，再进历史：即使请求失败也能看到自己问了什么
        Command echo = Command.println(Styles.PROMPT.render("❯ ") + Styles.USER.render(text));
        conversation.addUserMessage(text);

        if (llmClient == null) {
            return Command.batch(echo, Command.println(Styles.ERROR.render("✗ 还没有可用的 provider。")));
        }

        streaming = true;
        streamBuf.setLength(0);
        agentErrored = false;
        spinnerVerb = SpinnerVerbs.random();
        requestStartMillis = System.currentTimeMillis();

        // 把活儿交给 Agent：它在虚拟线程里跑 ReAct 主循环，事件回投到本事件循环
        Agent agent = new Agent(llmClient, registry, activeProvider.normalizedProtocol(), systemPrompt);
        agent.setPlanMode(planMode);
        agent.setWorkDir(workingDir);
        agent.setPermissionMode(permissionMode);
        agent.setMaxIterations(200);
        agent.setContextWindow(contextWindow);
        agent.setSessionId(sessionId);
        agent.setRecovery(recovery);
        agent.setBreaker(breaker);
        BlockingQueue<AgentEvent> queue = agent.run(conversation);
        Thread.startVirtualThread(() -> pumpAgentEvents(queue));
        return echo;
    }

    /** /mode 命令：切换权限模式（default / accept / plan / yolo）。 */
    private Command handleModeCommand(String arg) {
        return switch (arg) {
            case "default" -> {
                planMode = false;
                permissionMode = PermissionMode.DEFAULT;
                yield Command.println(Styles.ACCENT.render("✻ 权限模式: default（只读自动放行，写/命令需确认）"));
            }
            case "accept" -> {
                planMode = false;
                permissionMode = PermissionMode.ACCEPT_EDITS;
                yield Command.println(Styles.ACCENT.render("✻ 权限模式: acceptEdits（写文件自动放行，命令需确认）"));
            }
            case "plan" -> {
                planMode = true;
                permissionMode = PermissionMode.PLAN;
                conversation.addSystemReminder(
                        PlanModePrompt.buildReentryReminder(PlanModePrompt.getPlanPath(workingDir)));
                yield Command.println(Styles.ACCENT.render("✻ 权限模式: plan（同 default，仅规划不改动）"));
            }
            case "yolo" -> {
                planMode = false;
                permissionMode = PermissionMode.BYPASS;
                yield Command.println(Styles.ERROR.render("⚠ 权限模式: bypass（跳过全部确认，仅黑名单生效，危险！）"));
            }
            default -> Command.println(Styles.ERROR.render("用法: /mode default|accept|plan|yolo"));
        };
    }

    /** 用户在权限确认弹窗下的按键处理：y 允许 / a 始终允许 / n 拒绝。 */
    private Command handlePermissionKey(KeyPressMessage key) {
        String ch = "rune".equals(key.key()) ? String.valueOf(key.rune()) : key.key();
        PermissionResponse resp;
        if ("y".equalsIgnoreCase(ch)) {
            resp = PermissionResponse.ALLOW;
        } else if ("a".equalsIgnoreCase(ch)) {
            resp = PermissionResponse.ALLOW_ALWAYS;
        } else if ("n".equalsIgnoreCase(ch)) {
            resp = PermissionResponse.DENY;
        } else {
            return Command.none();
        }
        var fut = this.pendingPermission;
        this.awaitingPermission = false;
        this.pendingPermission = null;
        if (fut != null) {
            fut.complete(resp);
        }
        String label = switch (resp) {
            case ALLOW -> "允许";
            case ALLOW_ALWAYS -> "始终允许（已写入本地规则）";
            case DENY -> "拒绝";
        };
        return Command.println(Styles.ACCENT.render("✻ " + label));
    }

    /** 把 Agent 事件逐个投回事件循环；遇到终止事件就收工。 */
    private void pumpAgentEvents(BlockingQueue<AgentEvent> queue) {
        try {
            while (true) {
                AgentEvent event = queue.take();
                if (program != null) {
                    program.send(new AgentEventMessage(event));
                }
                if (event instanceof AgentEvent.LoopComplete || event instanceof AgentEvent.ErrorEvent) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Command handleAgentEvent(AgentEvent event) {
        return switch (event) {
            // 正文增量进缓冲区，界面立刻就能看到
            case AgentEvent.StreamText st -> {
                streamBuf.append(st.text());
                yield Command.none();
            }
            // 工具调用：把"动手"了什么打到 scrollback
            case AgentEvent.ToolUseEvent tu ->
                    Command.println(Styles.TOOL.render("⏺ " + tu.toolName() + " " + previewArgs(tu.args())));
            // 工具结果：只显示首行预览
            case AgentEvent.ToolResultEvent tr ->
                    Command.println(Styles.MUTED.render("  ↳ " + previewResult(tr.output())));
            // 累计 token 用量，留着状态栏展示
            case AgentEvent.UsageEvent ue -> {
                lastInputTokens = ue.inputTokens();
                lastOutputTokens = ue.outputTokens();
                usageAvailable = true;
                yield Command.none();
            }
            case AgentEvent.TurnComplete tc -> Command.none();
            case AgentEvent.CompactingEvent ce -> Command.println(Styles.ACCENT.render(
                    "✻ 压缩上下文: " + ce.beforeTokens() + " → " + ce.afterTokens() + " estimated tokens"));
            case AgentEvent.RetryEvent re ->
                    Command.println(Styles.MUTED.render("↻ " + re.reason()));
            // 权限请求：Agent Loop 已经阻塞在 future 上，这里弹出确认窗，等用户拍板
            case AgentEvent.PermissionRequestEvent pre -> {
                awaitingPermission = true;
                pendingPermission = pre.response();
                yield Command.println(
                        Styles.ACCENT.render("⚠ 权限请求: " + pre.description())
                                + Styles.MUTED.render("\n  (y) 允许 / (a) 始终允许 / (n) 拒绝"));
            }
            // 整个循环结束：缓冲区落成最终回复
            case AgentEvent.LoopComplete lc -> finishTurn(streamBuf.toString());
            // 出错：展示错误，程序不退出
            case AgentEvent.ErrorEvent ee -> failTurn(ee.message());
        };
    }

    /** 本轮正常结束：缓冲区渲染成 Markdown；历史消息由 Agent 负责落地。 */
    private Command finishTurn(String fullText) {
        if (agentErrored) {
            streaming = false;
            return Command.none();
        }
        long elapsed = System.currentTimeMillis() - requestStartMillis;
        streaming = false;
        streamBuf.setLength(0);

        lastElapsedMillis = elapsed;

        if (fullText.isBlank()) {
            return Command.println(Styles.MUTED.render("（本轮没有返回文本内容）"));
        }
        String rendered = MarkdownRenderer.render(fullText, Math.max(30, width - 2));
        String meta = Styles.MUTED.render("✻ " + spinnerVerb + " for " + formatSeconds(elapsed)
                + " · ↑" + lastInputTokens + " ↓" + lastOutputTokens);
        return Command.println(rendered + "\n" + meta);
    }

    /** 本轮失败：错误以独立样式展示，程序不退出，用户可以直接问下一句。 */
    private Command failTurn(String message) {
        streaming = false;
        agentErrored = true;
        String partial = streamBuf.toString();
        streamBuf.setLength(0);
        // 中断时已经收到的那部分照样落成消息，下一轮还能接着聊（历史由 Agent 管，
        // 这里只在 Agent 没落地时补一条，避免重复）
        if (!partial.isBlank() && conversation.getMessages().stream()
                .noneMatch(m -> partial.equals(m.getContent()))) {
            conversation.addAssistantMessage(partial);
        }
        return Command.println(Styles.ERROR.render("✗ " + message));
    }

    private void activateProvider(int index) {
        ProviderConfig cfg = providers.get(index);
        activeProvider = cfg;
        // 用真实 model 重建 System Prompt（Environment 段落会带上模型名），再建客户端
        this.systemPrompt = PromptBuilder.buildSystemPrompt(
                PromptBuilder.detectEnvironment(cfg.getModel()), null);
        this.contextWindow = cfg.getContextWindow();
        llmClient = LlmClient.create(cfg, systemPrompt);
    }

    // ───────────────────────── 渲染 ─────────────────────────

    private String viewProviderSelect() {
        List<String> lines = new ArrayList<>();
        lines.add(separator());
        lines.add(Styles.ACCENT.render("  请选择要使用的 provider"));
        lines.add("");
        for (int i = 0; i < providers.size(); i++) {
            boolean selected = i == selectedIndex;
            String marker = selected ? Styles.PROMPT.render(" ❯ ") : "   ";
            String label = providers.get(i).getName() + "  (" + providers.get(i).getModel() + ")";
            lines.add(marker + (selected ? Styles.USER.render(label) : Styles.MUTED.render(label)));
        }
        lines.add("");
        lines.add(Styles.MUTED.render("  ↑↓ 移动，Enter 确认，Ctrl+C 退出"));
        lines.add(separator());
        return String.join("\n", lines);
    }

    private String viewChat() {
        List<String> lines = new ArrayList<>();
        lines.add(separator());

        if (streaming) {
            long elapsed = System.currentTimeMillis() - requestStartMillis;
            lines.add(Styles.SPINNER.render("✻ " + SpinnerVerbs.frame() + " " + spinnerVerb + "… ("
                    + (elapsed / 1000) + "s)"));
            if (!streamBuf.isEmpty()) {
                lines.addAll(List.of(streamBuf.toString().split("\n", -1)));
            }
            lines.add(separator());
        }

        lines.addAll(renderInputBox());
        lines.add(renderStatusBar());
        return String.join("\n", lines);
    }

    /** 底部带边框的输入框，含 ❯ 提示符与占位文字（F7-d）。 */
    private List<String> renderInputBox() {
        int boxWidth = Math.max(20, width);
        int innerWidth = boxWidth - 4;

        List<String> rows = new ArrayList<>();
        String raw = input.toString();
        if (raw.isEmpty()) {
            String placeholder = planMode ? "Plan mode: 探索代码并给出计划…" : "Send a message...";
            rows.add(Styles.PROMPT.render("❯ ") + Styles.PLACEHOLDER.render(placeholder));
        } else {
            String[] logicalLines = raw.split("\n", -1);
            for (int i = 0; i < logicalLines.length; i++) {
                List<String> wrapped = wrapPlain(logicalLines[i], innerWidth - 2);
                for (int j = 0; j < wrapped.size(); j++) {
                    String prefix = (i == 0 && j == 0) ? Styles.PROMPT.render("❯ ") : "  ";
                    rows.add(prefix + wrapped.get(j));
                }
            }
        }

        List<String> out = new ArrayList<>();
        out.add(Styles.BORDER.render("╭" + "─".repeat(boxWidth - 2) + "╮"));
        for (String row : rows) {
            String padded = row + " ".repeat(Math.max(0, innerWidth - Ansi.displayWidth(row)));
            out.add(Styles.BORDER.render("│") + " " + padded + " " + Styles.BORDER.render("│"));
        }
        out.add(Styles.BORDER.render("╰" + "─".repeat(boxWidth - 2) + "╯"));
        return out;
    }

    /** 状态栏：左侧 provider 名，右侧模型名 + token 用量 + 本轮耗时；Plan Mode 时标 PLAN。 */
    private String renderStatusBar() {
        String left = activeProvider == null ? "-" : activeProvider.getName();
        if (planMode) {
            left += Styles.ACCENT.render(" · PLAN");
        }
        // 权限模式：default 不额外标注；accept/yolo 显式标出（plan 已由上方 PLAN 标出）
        if (permissionMode == PermissionMode.ACCEPT_EDITS) {
            left += Styles.ACCENT.render(" · ACCEPT");
        } else if (permissionMode == PermissionMode.BYPASS) {
            left += Styles.ERROR.render(" · BYPASS");
        }
        String right = activeProvider == null ? "-" : activeProvider.getModel();
        if (usageAvailable) {
            right += " · ↑" + lastInputTokens + " ↓" + lastOutputTokens;
        }
        if (lastElapsedMillis > 0) {
            right += " · " + formatSeconds(lastElapsedMillis);
        }
        int gap = Math.max(1, width - Ansi.displayWidth(left) - Ansi.displayWidth(right));
        return left + " ".repeat(gap) + right;
    }

    private String renderBanner() {
        String[] cat = {
                "   /\\_/\\",
                "  ( o.o )",
                "   > ^ <",
        };
        String[] info = {
                Styles.USER.render(APP_NAME + " v" + VERSION),
                Styles.MUTED.render(workingDir),
                "",
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cat.length; i++) {
            sb.append(cat[i]);
            if (i < info.length && !info[i].isEmpty()) {
                sb.append("   ").append(info[i]);
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** 启动后的一行就绪提示。 */
    private String renderReadyLine() {
        return Styles.ACCENT.render("✻ 已就绪")
                + Styles.MUTED.render(" · " + EXIT_COMMAND + " 退出 · /mode 切换权限模式 · /plan 进入计划模式 · /do 退出");
    }

    private String separator() {
        return Styles.BORDER.render("─".repeat(Math.max(10, width)));
    }

    // ───────────────────────── 小工具 ─────────────────────────

    /** 按可见宽度软换行。 */
    private static List<String> wrapPlain(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (maxWidth <= 1) {
            out.add(text);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        int w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int cw = cp > 0x2E80 ? 2 : 1;
            if (w + cw > maxWidth) {
                out.add(cur.toString());
                cur.setLength(0);
                w = 0;
            }
            cur.appendCodePoint(cp);
            w += cw;
            i += Character.charCount(cp);
        }
        out.add(cur.toString());
        return out;
    }

    private static String formatSeconds(long millis) {
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    /** 工具调用参数的紧凑预览：直接打印 Map，过长截断。 */
    private static String previewArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        String s = args.toString();
        if (s.length() > 100) {
            s = s.substring(0, 100) + "…";
        }
        return s;
    }

    /** 工具结果的紧凑预览：取首行，过长截断。 */
    private static String previewResult(String content) {
        if (content == null || content.isBlank()) {
            return "(空)";
        }
        String firstLine = content.split("\n", -1)[0];
        if (firstLine.length() > 120) {
            firstLine = firstLine.substring(0, 120) + "…";
        }
        int lines = (int) content.lines().count();
        return lines > 1 ? firstLine + " (+" + (lines - 1) + " 行)" : firstLine;
    }
}
