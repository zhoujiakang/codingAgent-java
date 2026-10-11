package com.agentjava.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个 MCP Server 的配置。
 *
 * <p>六个字段分两组：{@code command} + {@code args} + {@code env} 服务于 stdio 传输，
 * {@code url} + {@code headers} 服务于 Streamable HTTP 传输。{@code name} 是唯一标识，
 * 既用作连接管理器的字典 key，也参与工具名拼接（{@code mcp__<name>__<tool>}）。
 */
public class McpServerConfig {

    private String name;
    private String command;
    private List<String> args;
    private String url;
    private Map<String, String> headers;
    private Map<String, String> env;

    public McpServerConfig() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    /** 从 YAML 里一个 server 节点构造配置。 */
    @SuppressWarnings("unchecked")
    public static McpServerConfig fromYaml(String name, Map<String, Object> node) {
        var cfg = new McpServerConfig();
        cfg.name = name;
        cfg.command = str(node.get("command"));
        cfg.url = str(node.get("url"));
        cfg.args = list(node.get("args"));
        cfg.headers = map(node.get("headers"));
        cfg.env = map(node.get("env"));
        return cfg;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> list(Object v) {
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (var e : l) {
                if (e != null) {
                    out.add(e.toString());
                }
            }
            return out;
        }
        return null;
    }

    private static Map<String, String> map(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(e.getKey().toString(), e.getValue().toString());
                }
            }
            return out;
        }
        return null;
    }
}
