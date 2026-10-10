package com.agentjava.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具执行结果。
 *
 * <p>工具执行失败不是程序异常，而是返回 {@code isError=true} 让模型看到错误后调整参数重试。
 * 这样工具调用失败时流程不会被打断，模型能自我纠正。
 *
 * <p>{@code contentBlocks} 是给 tool_result 用的结构化内容块，为空时按 {@code output}
 * 发纯文本。目前内置工具都不用，保留它是为了协议完整性。
 */
public record ToolResult(String output, boolean isError, List<Map<String, Object>> contentBlocks) {

    public ToolResult(String output, boolean isError) {
        this(output, isError, null);
    }

    public static ToolResult success(String output) {
        return new ToolResult(output, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }

    /** 带结构化内容块的成功结果，output 作为拿不到块时的兜底文本。 */
    public static ToolResult withContentBlocks(String output, List<Map<String, Object>> blocks) {
        return new ToolResult(output, false, blocks);
    }
}
