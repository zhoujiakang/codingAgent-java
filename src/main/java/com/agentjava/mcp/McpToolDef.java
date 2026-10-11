package com.agentjava.mcp;

import java.util.Map;

/**
 * MCP Server 返回的一个工具定义：名称、描述、输入 JSON Schema。
 *
 * <p>对应协议里 {@code tools/list} 结果中 {@code tools} 数组的单个元素。它会被
 * {@link McpToolWrapper} 包成本 Agent 内部的 {@code Tool} 接口。
 */
public record McpToolDef(String name, String description, Map<String, Object> inputSchema) {

    @SuppressWarnings("unchecked")
    public static McpToolDef fromMap(Map<String, Object> m) {
        String name = m.get("name") instanceof String s ? s : "";
        String description = m.get("description") instanceof String s ? s : "";
        Object schema = m.get("inputSchema");
        Map<String, Object> input = schema instanceof Map<?, ?> sm ? (Map<String, Object>) sm : Map.of();
        return new McpToolDef(name, description, input);
    }
}
