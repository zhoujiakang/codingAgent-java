package com.agentjava.agent;

import com.agentjava.permission.PermissionChecker;
import com.agentjava.permission.PermissionMode;
import com.agentjava.permission.PermissionResponse;
import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolCategory;
import com.agentjava.tool.ToolRegistry;
import com.agentjava.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 工具执行器：负责"按批次执行一轮里模型发起的所有工具调用"。
 *
 * 分批策略是这里的核心——只读工具（READ）可以安全地并发跑，写文件和命令
 * 类工具一旦并发谁先落盘就说不准，所以各自独占一批、严格串行。
 *
 * 每执行一个工具前先过一遍 {@link PermissionChecker}：被拒绝（DENY）或用户拒绝
 * 都包成 isError 的工具结果回灌给模型（不终止循环，让模型自己调整策略）；
 * 需要确认（ASK）则发 {@link AgentEvent.PermissionRequestEvent} 并阻塞等待 UI 回复。
 */
public class StreamingExecutor {

    private final ToolRegistry registry;
    private final BlockingQueue<AgentEvent> eventQueue;
    private final PermissionChecker checker;

    public record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {
    }

    public record ToolExecResult(String toolId, String output, boolean isError) {
    }

    public StreamingExecutor(ToolRegistry registry, BlockingQueue<AgentEvent> eventQueue, PermissionChecker checker) {
        this.registry = registry;
        this.eventQueue = eventQueue;
        this.checker = checker;
    }

    /**
     * 执行这一轮的全部工具调用：先切批，再按批次顺序执行。
     * READ 批次内部用虚拟线程并发；写/命令批次串行。批与批之间严格顺序。
     */
    public List<ToolExecResult> executeAll(List<ToolCallInfo> calls) {
        var batches = partitionToolCalls(calls);
        var results = new ArrayList<ToolExecResult>();

        for (var batch : batches) {
            if (batch.concurrent() && batch.calls().size() > 1) {
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures = batch.calls().stream()
                            .map(call -> executor.submit(() -> executeSingle(call)))
                            .toList();
                    for (var future : futures) {
                        try {
                            results.add(future.get());
                        } catch (Exception ignored) {
                            // 单任务异常已在 executeSingle 内兜底成错误结果，
                            // 这里只是线程池编排层面的异常，忽略即可。
                        }
                    }
                }
            } else {
                for (var call : batch.calls()) {
                    results.add(executeSingle(call));
                }
            }
        }
        return results;
    }

    /** 私有记录：一批调用 + 这批能不能并发。 */
    private record ToolBatch(boolean concurrent, List<ToolCallInfo> calls) {
    }

    /**
     * 按相邻性切批：连续的只读调用合成一个并发批，写/命令调用各自独占一批。
     * 只跟上一批比较，保留模型给的顺序——[Read, Read, Edit, Read, Read]
     * 会切成 [Read,Read] 并发、[Edit] 串行、[Read,Read] 并发。
     */
    private List<ToolBatch> partitionToolCalls(List<ToolCallInfo> calls) {
        var batches = new ArrayList<ToolBatch>();
        for (var call : calls) {
            Tool tool = registry.get(call.toolName());
            // 安不安全按这一次调用的实际参数判断（Bash 的 ls 和 rm 都算 Bash，
            // 前者能跟 Read 并发，后者必须独占）。
            boolean safe = tool != null && tool.isConcurrencySafe(call.args());

            if (safe && !batches.isEmpty() && batches.get(batches.size() - 1).concurrent()) {
                batches.get(batches.size() - 1).calls().add(call);
            } else {
                batches.add(new ToolBatch(safe, new ArrayList<>(List.of(call))));
            }
        }
        return batches;
    }

    /** 单个工具执行管线：权限检查 → 查找 → 执行 → 计时 → 回报 UI。 */
    private ToolExecResult executeSingle(ToolCallInfo call) {
        Tool tool = registry.get(call.toolName());
        if (tool == null) {
            putSafe(new AgentEvent.ToolResultEvent(
                    call.toolId(), call.toolName(), "Unknown tool", true, 0));
            return new ToolExecResult(call.toolId(), "Error: unknown tool '" + call.toolName() + "'", true);
        }

        // 权限检查：被拒绝或用户拒绝都包成错误结果回灌，不终止循环
        PermissionChecker.CheckResult check = checker.check(tool, call.args());
        if (check.decision() == PermissionMode.Decision.DENY) {
            String msg = "Permission denied: " + check.reason();
            putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
            return new ToolExecResult(call.toolId(), msg, true);
        }
        PermissionResponse response = (check.decision() == PermissionMode.Decision.ASK)
                ? askUserPermission(call)
                : PermissionResponse.ALLOW;
        if (response == PermissionResponse.DENY) {
            String msg = "User denied this operation";
            putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
            return new ToolExecResult(call.toolId(), msg, true);
        }
        if (response == PermissionResponse.ALLOW_ALWAYS) {
            String content = checker.contentOf(call.toolName(), call.args());
            checker.appendLocalRule(call.toolName(), PermissionChecker.buildAlwaysAllowPattern(call.toolName(), content));
        }

        long start = System.nanoTime();
        ToolResult result;
        try {
            result = tool.execute(call.args());
        } catch (Exception e) {
            result = ToolResult.error("Tool execution error: " + e.getMessage());
        }
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;

        putSafe(new AgentEvent.ToolResultEvent(
                call.toolId(), call.toolName(), result.output(), result.isError(), elapsed));
        return new ToolExecResult(call.toolId(), result.output(), result.isError());
    }

    /** 发出权限请求事件并阻塞等待 UI 回传；超时（5 分钟）视为拒绝。 */
    private PermissionResponse askUserPermission(ToolCallInfo call) {
        var future = new CompletableFuture<PermissionResponse>();
        String desc = checker.describeToolAction(call.toolName(), call.args());
        putSafe(new AgentEvent.PermissionRequestEvent(call.toolName(), desc, future));
        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            return PermissionResponse.DENY;
        }
    }

    private void putSafe(AgentEvent event) {
        try {
            eventQueue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
