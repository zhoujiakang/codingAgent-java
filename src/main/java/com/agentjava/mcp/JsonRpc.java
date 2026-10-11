package com.agentjava.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-RPC 2.0 编解码与消息构造工具。
 *
 * <p>MCP 的消息格式统一是 JSON-RPC 2.0，只有三种消息：带 {@code id} 的请求、带 {@code id}
 * 的响应、不带 {@code id} 的通知。本类用 Jackson 做序列化/反序列化，并提供几个构造入口。
 *
 * <p>实现上 MCP 客户端只和 {@code Map<String,Object>} 打交道——请求体是 Map，响应体也反序列
 * 化成 Map，再用 {@code instanceof} 模式匹配按字段提取。这样既不需要为每个方法定义强类型，
 * 也能灵活适配不同版本协议，与官方 Java SDK 直接读取 {@code Map} 的做法一致。
 */
public final class JsonRpc {

    public static final String JSONRPC = "2.0";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonRpc() {
    }

    public static String serialize(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new McpException("序列化 JSON-RPC 消息失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deserialize(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new McpException("解析 JSON-RPC 消息失败: " + e.getMessage(), e);
        }
    }

    /** 构造请求：jsonrpc + id + method + params。 */
    public static Map<String, Object> request(Object id, String method, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", JSONRPC);
        m.put("id", id);
        m.put("method", method);
        m.put("params", params == null ? Map.of() : params);
        return m;
    }

    /** 构造通知：有 method 无 id（服务器不回响应）。 */
    public static Map<String, Object> notification(String method, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", JSONRPC);
        m.put("method", method);
        m.put("params", params == null ? Map.of() : params);
        return m;
    }

    /** 取 id；通知没有 id，返回 null。注意 id 为 0 时 Map 里仍有 "id" 键，故用 containsKey 区分。 */
    public static Object getId(Map<String, Object> msg) {
        return msg.get("id");
    }

    public static boolean hasError(Map<String, Object> msg) {
        return msg.containsKey("error");
    }

    public static String errorMessage(Map<String, Object> msg) {
        Object err = msg.get("error");
        if (err instanceof Map<?, ?> e) {
            Object code = e.get("code");
            Object message = e.get("message");
            return (code != null ? code + ": " : "") + (message != null ? message.toString() : "unknown error");
        }
        return "unknown error";
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> resultOf(Map<String, Object> msg) {
        Object r = msg.get("result");
        if (r instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }
}
