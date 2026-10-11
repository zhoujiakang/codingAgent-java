package com.agentjava.agent;

import com.agentjava.conversation.ConversationManager;
import com.agentjava.conversation.ToolResultBlock;
import com.agentjava.conversation.ToolResultBudget;
import com.agentjava.conversation.ToolUseBlock;
import com.agentjava.compact.ContextCompactor;
import com.agentjava.compact.RecoveryState;
import com.agentjava.hook.EventName;
import com.agentjava.hook.HookContext;
import com.agentjava.hook.HookEngine;
import com.agentjava.llm.LlmClient;
import com.agentjava.llm.StreamEvent;
import com.agentjava.permission.PermissionChecker;
import com.agentjava.permission.PermissionMode;
import com.agentjava.prompt.PlanModePrompt;
import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolCategory;
import com.agentjava.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Agent：持有 LLM 客户端、工具注册中心和配置，驱动核心的 ReAct 主循环。
 *
 * 主循环就是反复做"调 LLM → 收集工具调用 → 执行工具 → 把结果喂回"这一件事，
 * 直到模型不再调用工具（任务完成）或触达某个停止条件。循环内部跑在虚拟线程里，
 * 所有进展通过 {@link AgentEvent} 队列推给 UI，Agent 本身完全不认识界面。
 *
 * 职责范围：Agent Loop + AgentEvent + 流式收集 + 工具分批执行 + Plan Mode。
 * 权限系统（PermissionChecker 五层防御）与 HITL 确认机制在此之上接入
 * （权限请求事件 → UI 阻塞等待 → 回传决策），上下文压缩另行处理。
 */
public class Agent {

    /** Plan Mode 下计算 plan 文件路径时使用的工作目录；为 null 时退回 JVM 的 user.dir。 */
    private String workDir;

    private final LlmClient client;
    private final ToolRegistry registry;
    private final String protocol;
    private final String baseSystemPrompt;

    private int maxIterations = 100;
    private boolean planMode = false;

    /** 权限模式（默认 default：只读放行，写/命令需确认）。 */
    private PermissionMode permissionMode = PermissionMode.DEFAULT;

    /** 权限检查器；工作目录就绪后构建，权限模式变化时同步更新。 */
    private PermissionChecker checker;

    /** 上下文窗口大小（token），压缩阈值据此计算。 */
    private int contextWindow = 200_000;

    /** 会话 ID，用于隔离各会话的工具结果溢写目录。 */
    private String sessionId = "default";

    /** 最近访问的文件 / 激活的 Skill 记录，压缩后的恢复块从这里取。跨轮次累积。 */
    private RecoveryState recovery = new RecoveryState();

    /** 自动压缩熔断器：连续 3 次摘要失败则熔断，停止自动触发。 */
    private ContextCompactor.AutoCompactTrackingState breaker =
            new ContextCompactor.AutoCompactTrackingState();

    /** 紧急压缩重试计数：API 返回上下文超长时最多重试 3 次。 */
    private int contextRetries = 0;

    /**
     * Hook 引擎；由 TUI 层注入并透传给 {@link StreamingExecutor}。
     *
     * <p>消息级（{@code pre_send} / {@code post_receive}）与 {@code compact} 事件的插入点
     * 在主循环里，工具级那几个在 {@link StreamingExecutor} 里。为 null 时全部退化成空操作。
     */
    private HookEngine hookEngine;

    public Agent(LlmClient client, ToolRegistry registry, String protocol, String baseSystemPrompt) {
        this.client = client;
        this.registry = registry;
        this.protocol = protocol;
        this.baseSystemPrompt = baseSystemPrompt == null ? "" : baseSystemPrompt;
    }

    public void setMaxIterations(int max) {
        this.maxIterations = max;
    }

    public void setPlanMode(boolean on) {
        this.planMode = on;
    }

    public boolean isPlanMode() {
        return planMode;
    }

    /** 设置权限模式；会同步到检查器。 */
    public void setPermissionMode(PermissionMode mode) {
        this.permissionMode = mode == null ? PermissionMode.DEFAULT : mode;
        if (checker != null) {
            checker.setMode(this.permissionMode);
        }
    }

    public PermissionMode getPermissionMode() {
        return permissionMode;
    }

    /** 设置工作目录，供 Plan Mode 计算 plan 文件路径（默认 JVM user.dir）。 */
    public void setWorkDir(String workDir) {
        this.workDir = workDir;
        this.checker = new PermissionChecker(permissionMode,
                workDir != null ? Path.of(workDir) : null);
    }

    /** 设置上下文窗口大小（token）。 */
    public void setContextWindow(int contextWindow) {
        if (contextWindow > 0) {
            this.contextWindow = contextWindow;
        }
    }

    /** 设置会话 ID，用于隔离各会话的工具结果溢写目录。 */
    public void setSessionId(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            this.sessionId = sessionId;
        }
    }

    /** 注入跨轮次累积的恢复状态（最近访问的文件 / 激活的 Skill）。 */
    public void setRecovery(RecoveryState recovery) {
        if (recovery != null) {
            this.recovery = recovery;
        }
    }

    /** 注入自动压缩熔断器（跨轮次累积）。 */
    public void setBreaker(ContextCompactor.AutoCompactTrackingState breaker) {
        if (breaker != null) {
            this.breaker = breaker;
        }
    }

    /** 注入 Hook 引擎；为 null 表示没有配置任何 Hook。 */
    public void setHookEngine(HookEngine hookEngine) {
        this.hookEngine = hookEngine;
    }

    /**
     * 启动 Agent Loop，立即返回一个事件队列。循环跑在后台虚拟线程里，
     * 调用方拿到队列后逐个取事件即可（最后一个事件一定是 LoopComplete 或 ErrorEvent）。
     */
    public BlockingQueue<AgentEvent> run(ConversationManager conv) {
        var queue = new LinkedBlockingQueue<AgentEvent>(64);
        Thread.startVirtualThread(() -> {
            try {
                agentLoop(conv, queue);
            } catch (Exception e) {
                putSafe(queue, new AgentEvent.ErrorEvent("Agent error: " + e.getMessage()));
            }
        });
        return queue;
    }

    private void agentLoop(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
        int totalInput = 0, totalOutput = 0;
        boolean loopCompleted = false;

        try {
            for (int iteration = 1; ; iteration++) {
                // 停止条件一：迭代次数上限
                if (maxIterations > 0 && iteration > maxIterations) {
                    putSafe(queue, new AgentEvent.ErrorEvent(
                            "Agent reached maximum iterations (%d)".formatted(maxIterations)));
                    loopCompleted = true;
                    break;
                }

                // 停止条件二：用户取消（上层 thread.interrupt()）
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }

                // Plan Mode：工具只暴露只读类（硬约束），且通过 system-reminder 按轮次
                // 注入提醒（软约束）。System Prompt 本身保持不变，以便 Prompt Cache 持续命中。
                List<Tool> tools = planMode
                        ? registry.listTools().stream()
                        .filter(t -> t.category() == ToolCategory.READ)
                        .toList()
                        : registry.listTools();
                var schemas = registry.schemasOf(tools, protocol);
                client.setSystemPrompt(baseSystemPrompt);

                if (planMode) {
                    String planPath = PlanModePrompt.getPlanPath(workDir);
                    boolean exists = PlanModePrompt.planExists(planPath);
                    conv.addSystemReminder(PlanModePrompt.buildReminder(planPath, exists, iteration));
                }

                // 每轮调 API 前：检查是否需要自动压缩（软硬双阈值 + 熔断）。
                // 压缩前先算一次 token，压缩后再算一次，用差值提示 UI「记忆被重新打包了」。
                int preTokens = ContextCompactor.currentTokens(conv.getMessages(),
                        conv.hasUsageAnchor()
                                ? new ContextCompactor.UsageAnchor(conv.getBaselineTokens(), conv.getAnchorCount())
                                : null);
                String compactSummary = ContextCompactor.autoCompact(
                        conv, client, contextWindow, workDir, sessionId, recovery, schemas, breaker);
                if (!compactSummary.isEmpty()) {
                    int postTokens = ContextCompactor.currentTokens(conv.getMessages(), null);
                    putSafe(queue, new AgentEvent.CompactingEvent(preTokens, postTokens));
                    fireHook(HookContext.of(EventName.COMPACT));
                }

                // pre_send：请求发出之前的最后一道口子（消息预处理）
                fireHook(HookContext.of(EventName.PRE_SEND));

                // 调 LLM，拿事件队列
                var streamQueue = client.stream(conv, schemas);

                // 消费流式响应，一边透传文本一边收集工具调用
                var text = new StringBuilder();
                var toolCalls = new ArrayList<ToolCallInfo>();
                String stopReason = "end_turn";
                int turnInput = 0, turnOutput = 0;
                int turnCacheRead = 0, turnCacheCreation = 0;
                boolean streamError = false;
                String lastError = null;

                while (true) {
                    StreamEvent event;
                    try {
                        event = streamQueue.poll(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (event == null) {
                        putSafe(queue, new AgentEvent.ErrorEvent("Stream timeout"));
                        streamError = true;
                        lastError = "Stream timeout";
                        break;
                    }

                    switch (event) {
                        case StreamEvent.TextDelta td -> {
                            text.append(td.text());
                            putSafe(queue, new AgentEvent.StreamText(td.text()));
                        }
                        // 扩展思考按约定接收即丢弃，不渲染
                        case StreamEvent.ThinkingDelta ignored -> {
                        }
                        // ToolCallStart / ToolCallDelta 只是分片信号，参数还没收齐，
                        // 真正的工具调用事件等参数完整（ToolCallComplete）再发一次即可
                        case StreamEvent.ToolCallStart ignored -> {
                        }
                        case StreamEvent.ToolCallDelta ignored -> {
                        }
                        case StreamEvent.ToolCallComplete tcc -> {
                            toolCalls.add(new ToolCallInfo(tcc.toolId(), tcc.toolName(), tcc.arguments()));
                            putSafe(queue, new AgentEvent.ToolUseEvent(
                                    tcc.toolId(), tcc.toolName(), tcc.arguments()));
                        }
                        case StreamEvent.StreamEnd se -> {
                            stopReason = se.stopReason();
                            turnInput = se.inputTokens();
                            turnOutput = se.outputTokens();
                            turnCacheRead = se.cacheReadTokens();
                            turnCacheCreation = se.cacheCreationTokens();
                        }
                        case StreamEvent.Error err -> {
                            streamError = true;
                            lastError = err.message();
                            putSafe(queue, new AgentEvent.ErrorEvent(err.message()));
                        }
                    }

                    if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) {
                        break;
                    }
                }

                // post_receive：响应收完之后（消息后处理）
                fireHook(HookContext.of(EventName.POST_RECEIVE));

                // 流出错：若是上下文超长，紧急压缩后重试本轮请求（不把错误写进历史）
                if (streamError) {
                    if (isContextTooLong(lastError) && contextRetries < 3) {
                        contextRetries++;
                        ContextCompactor.forceCompact(
                                conv, client, contextWindow, workDir, sessionId, recovery, schemas);
                        conv.clearUsageAnchor();
                        continue;
                    }
                    if (lastError != null) {
                        conv.addErrorMessage(lastError);
                    }
                    loopCompleted = true;
                    break;
                }

                // 累计 token 用量
                totalInput += turnInput;
                totalOutput += turnOutput;
                putSafe(queue, new AgentEvent.UsageEvent(totalInput, totalOutput));

                // 保存本轮助手消息（正文 + 工具调用块），并锚定本轮真实用量
                var toolUseBlocks = toolCalls.stream()
                        .map(tc -> new ToolUseBlock(tc.toolId(), tc.toolName(), tc.args()))
                        .toList();
                conv.addAssistantMessageWithTools(text.toString(), toolUseBlocks);
                conv.recordUsageAnchor(turnInput, turnOutput, turnCacheRead, turnCacheCreation);

                // 停止条件三：模型不再调用工具 → 任务完成
                if (toolCalls.isEmpty()) {
                    putSafe(queue, new AgentEvent.LoopComplete(iteration));
                    loopCompleted = true;
                    break;
                }

                // 执行工具：分批 + 只读并发，执行前经权限检查器逐层判定
                var executor = new StreamingExecutor(registry, queue, checker, hookEngine);
                var callInfos = toolCalls.stream()
                        .map(tc -> new StreamingExecutor.ToolCallInfo(tc.toolId(), tc.toolName(), tc.args()))
                        .toList();
                var results = executor.executeAll(callInfos);

                // ── Layer 1：工具结果预算（入历史前完成，写入即终态）──
                // 1) 先算豁免集：模型回读之前溢写的文件时，读回内容同样巨大，照常溢写会永远看不到全文
                Path spillDir = ToolResultBudget.spillDir(workDir, sessionId);
                Set<String> exemptIds = new HashSet<>();
                for (var tc : toolCalls) {
                    if (ToolResultBudget.isSpillReadback(tc.toolName(), tc.args(), spillDir)) {
                        exemptIds.add(tc.toolId());
                    }
                }
                // 2) 逐条组装，单条超阈值先写盘换预览，溢过即加入豁免集
                var resultBlocks = new ArrayList<ToolResultBlock>(results.size());
                for (var r : results) {
                    String content = r.output();
                    if (content.length() > ToolRegistry.MAX_OUTPUT_CHARS
                            && !exemptIds.contains(r.toolId())) {
                        content = ToolResultBudget.persistLargeResult(spillDir, r.toolId(), r.output());
                        exemptIds.add(r.toolId());
                    }
                    resultBlocks.add(new ToolResultBlock(r.toolId(), content, r.isError()));
                }
                // 3) 聚合预算：一轮并行结果合计超 200K 字符时，从最大的非豁免结果开始落盘
                var budgeted = ToolResultBudget.apply(resultBlocks, spillDir, exemptIds);
                conv.addToolResultsMessage(budgeted);

                // 记录最近访问的文件，供后续压缩的恢复块使用
                for (int i = 0; i < toolCalls.size(); i++) {
                    var tc = toolCalls.get(i);
                    var r = results.get(i);
                    if (tc.args() != null && tc.args().get("file_path") instanceof String fp
                            && Set.of("ReadFile", "EditFile", "WriteFile").contains(tc.toolName())) {
                        recovery.recordFile(fp, r.output());
                    }
                }

                putSafe(queue, new AgentEvent.TurnComplete(iteration));
            }
        } finally {
            if (!loopCompleted) {
                putSafe(queue, new AgentEvent.LoopComplete(0));
            }
        }
    }

    /** 触发一个通知型事件；没有 Hook 引擎时是空操作。 */
    private void fireHook(HookContext ctx) {
        if (hookEngine != null) {
            hookEngine.runHooks(ctx);
        }
    }

    /** 工具调用的本地载体：贯穿"收集 → 执行"两个阶段。 */
    private record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {
    }

    /** 判断错误是否由上下文超长引起（紧急压缩的触发条件）。 */
    private static boolean isContextTooLong(String err) {
        if (err == null) {
            return false;
        }
        String lower = err.toLowerCase();
        return lower.contains("context") || lower.contains("too long") || lower.contains("prompt");
    }

    private static void putSafe(BlockingQueue<AgentEvent> queue, AgentEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
