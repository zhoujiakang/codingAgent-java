package com.agentjava.hook;

/**
 * 单条 Hook 的执行结果。
 *
 * <p>结果有两个去处：调用方拿到的返回值里有一份（同步路径），
 * 引擎的 {@code notifications} 队列里也有一份——异步 Hook 的结果只能从队列里取。
 * TUI 每轮结束 {@code drainNotifications()} 一次，prompt 类型的输出被包成
 * {@code <hook-notification>} 注入对话，让 LLM 下一轮看到。
 */
public record HookResult(
        /** Hook 标识：有 id 用 id，没有就用事件名。 */
        String hookId,
        EventName event,
        ActionType actionType,
        String output,
        boolean success,
        boolean reject
) {

    /** 这条结果要不要作为提示词注入对话上下文。 */
    public boolean isPromptNotification() {
        return actionType == ActionType.PROMPT
                && output != null
                && !output.isBlank();
    }
}
