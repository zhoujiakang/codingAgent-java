package com.agentjava.hook;

/**
 * 工具调用被 Hook 拦截。
 *
 * <p>{@code pre_tool_use} 上标记了 {@code reject}（或动作失败且 {@code on_error: reject}）
 * 的 Hook 一旦命中，引擎就返回它，工具调用被取消。Agent Loop 拿它做两件事：
 * 跳过真实执行 + 把 {@link #reason()} 当作工具结果（isError）回灌给 LLM。
 *
 * <p>这就是反馈循环：Hook 拦截 → Agent 看到原因 → Agent 换一种方式继续干。
 */
public record ToolRejectedError(
        /** 被拦下的工具名。 */
        String toolName,
        /** 拒绝原因：默认取 Hook 的动作输出，动作没输出时由引擎补一句兜底话术。 */
        String reason,
        /** 拦下它的 Hook id；没有配 id 时为 null。 */
        String hookId
) {

    @Override
    public String toString() {
        String who = hookId == null || hookId.isBlank() ? "" : " [" + hookId + "]";
        return "ToolRejectedError{tool=" + toolName + who + ", reason=" + reason + "}";
    }
}
