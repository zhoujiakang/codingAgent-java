package com.agentjava.conversation;

import java.util.List;
import java.util.Map;

/**
 * 一条工具结果在对话历史里的形态。
 *
 * <p>{@code contentBlocks} 非空时，发请求会用这些结构化块代替 {@code content} 纯文本。
 * 内置工具只用 content 纯文本，contentBlocks 留空。
 */
public record ToolResultBlock(String toolUseId, String content, boolean isError,
                             List<Map<String, Object>> contentBlocks) {

    public ToolResultBlock(String toolUseId, String content, boolean isError) {
        this(toolUseId, content, isError, null);
    }
}
