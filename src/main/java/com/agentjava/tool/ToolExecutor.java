package com.agentjava.tool;

import com.agentjava.conversation.ToolResultBlock;
import com.agentjava.conversation.ToolUseBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具执行器。
 *
 * <p>把一次模型返回的工具调用（可能有多个）逐个查表、执行、包装成 tool_result 块。
 * 工具抛异常或找不到都降级成 isError 结果，绝不让单次工具失败中断整个流程。
 */
public final class ToolExecutor {

    private ToolExecutor() {
    }

    public static List<ToolResultBlock> execute(ToolRegistry registry, List<ToolUseBlock> calls) {
        var blocks = new ArrayList<ToolResultBlock>();
        for (var call : calls) {
            Tool tool = registry.get(call.toolName());
            ToolResult result;
            if (tool == null) {
                result = ToolResult.error("Error: unknown tool: " + call.toolName());
            } else {
                try {
                    result = tool.execute(call.arguments());
                } catch (Exception e) {
                    result = ToolResult.error("Error: tool execution failed: " + e.getMessage());
                }
            }
            blocks.add(new ToolResultBlock(
                    call.toolUseId(),
                    result.output() == null ? "" : result.output(),
                    result.isError()));
        }
        return blocks;
    }
}
