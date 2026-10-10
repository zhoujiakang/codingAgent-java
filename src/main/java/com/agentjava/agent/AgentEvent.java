package com.agentjava.agent;

import java.util.Map;

/**
 * Agent 与 UI 之间的通信协议。
 *
 * 用 sealed interface 把事件类型锁死，用 record 定义每个事件。这样 Agent 完全
 * 不知道 UI 长什么样，UI 也不知道 Agent 内部跑了几轮循环——两边只通过这一条
 * 事件流解耦。switch 分发时编译器还能做穷举检查，漏掉哪个分支会直接报错。
 *
 * 这里只保留和 Agent Loop 相关的事件；权限请求、用户确认这类事件属于后续能力。
 */
public sealed interface AgentEvent {

    /** 正文文本增量：Agent 一边收流一边透传给 UI。 */
    record StreamText(String text) implements AgentEvent {
    }

    /** 模型发起一次工具调用（带着完整的参数）。 */
    record ToolUseEvent(String toolId, String toolName, Map<String, Object> args) implements AgentEvent {
    }

    /** 单个工具执行完毕（附带耗时，UI 用来显示"跑了多久"）。 */
    record ToolResultEvent(String toolId, String toolName, String output,
                          boolean isError, double elapsed) implements AgentEvent {
    }

    /** 一轮（一次模型往返 + 一轮工具执行）结束。 */
    record TurnComplete(int turn) implements AgentEvent {
    }

    /** 整个 Agent Loop 结束（正常收尾或异常兜底）。 */
    record LoopComplete(int totalTurns) implements AgentEvent {
    }

    /** 累计 token 用量，随轮次累加。 */
    record UsageEvent(int inputTokens, int outputTokens) implements AgentEvent {
    }

    /** 出错：Agent 把异常包装成事件推给 UI，不会默默退出循环。 */
    record ErrorEvent(String message) implements AgentEvent {
    }

    /** 需要等待后重试（例如限流），UI 可以提示用户。 */
    record RetryEvent(String reason, long waitMs) implements AgentEvent {
    }
}
