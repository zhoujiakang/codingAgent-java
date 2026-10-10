package com.agentjava.conversation;

import java.util.Map;

/**
 * 一条工具调用在对话历史里的形态。
 *
 * <p>与 {@link ToolResultBlock} 通过 {@code toolUseId} 配对：模型发起一次 tool_use，
 * 我们执行后必须回一条带同样 id 的 tool_result。
 */
public record ToolUseBlock(String toolUseId, String toolName, Map<String, Object> arguments) {
}
