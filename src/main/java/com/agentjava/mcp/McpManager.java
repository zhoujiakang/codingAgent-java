package com.agentjava.mcp;

import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 连接调度中心：按配置批量连接 Server、发现工具、包装注册、统一关闭。
 *
 * <p>职责边界（从配置到调用的完整流程）：
 * <ol>
 *   <li>持有配置列表（项目级覆盖用户级，已在外层合并好）。</li>
 *   <li>启动时连接所有 Server，逐个发现工具并包装成 {@link McpToolWrapper} 注册进 {@link ToolRegistry}。</li>
 *   <li><b>容错</b>：单个 Server 失败（握手失败、网络不通、版本不支持）只收集错误，
 *       不阻断其他 Server，也不阻止本 Agent 启动。</li>
 *   <li>退出时统一关闭所有连接，释放子进程与传输资源。</li>
 * </ol>
 */
public class McpManager {

    private final List<McpServerConfig> configs;
    private final Map<String, McpClient> clients = new LinkedHashMap<>();

    public McpManager(List<McpServerConfig> configs) {
        this.configs = configs != null ? configs : List.of();
    }

    /** 一次连接+注册的结果：成功注册的工具、连上的 Server 数、失败数与错误明细。 */
    public record ConnectResult(List<Tool> tools, int serverCount, int failureCount, List<String> errors) {
    }

    /**
     * 连接所有 Server，把它们的工具注册到 registry。
     * 即使部分 Server 失败也照常返回，错误收集在 {@link ConnectResult#errors()} 里。
     */
    public ConnectResult registerAllTools(ToolRegistry registry) {
        List<Tool> tools = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int failures = 0;
        for (var cfg : configs) {
            try {
                McpClient client = new McpClient(cfg);
                client.connect();
                clients.put(cfg.getName(), client);
                for (var def : client.listTools()) {
                    tools.add(new McpToolWrapper(cfg.getName(), def, client));
                }
            } catch (Exception e) {
                failures++;
                errors.add("MCP Server '" + cfg.getName() + "': " + e.getMessage());
            }
        }
        for (var t : tools) {
            registry.register(t);
        }
        return new ConnectResult(tools, clients.size(), failures, errors);
    }

    public void shutdown() {
        for (var client : clients.values()) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
        clients.clear();
    }

    public int connectedCount() {
        return clients.size();
    }
}
