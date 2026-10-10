package com.agentjava.tool;

import com.agentjava.tool.impl.BashTool;
import com.agentjava.tool.impl.EditFileTool;
import com.agentjava.tool.impl.GlobTool;
import com.agentjava.tool.impl.GrepTool;
import com.agentjava.tool.impl.ReadFileTool;
import com.agentjava.tool.impl.WriteFileTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心。
 *
 * <p>集中管理内置工具的注册、schema 生成与查找。每个协议下发给模型的 tools
 * 结构略有不同（Anthropic 用 name/description/input_schema，OpenAI 用
 * type:function + function 包裹），{@link #getAllSchemas(String)} 负责按协议适配。
 */
public class ToolRegistry {

    /**
     * 单条工具结果进入对话历史前的溢写阈值：超过这个字符数就写盘留预览。
     * 定在 50000，让模型一次能看到足够多内容，不必为了看全结果再发一轮 ReadFile。
     */
    public static final int MAX_OUTPUT_CHARS = 50_000;

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Tool> listTools() {
        return List.copyOf(tools.values());
    }

    /**
     * 按协议生成全部工具的 schema 列表，直接作为发给 LLM API 的 tools 参数。
     *
     * @param protocol 协议名：anthropic / openai / openai-compat
     */
    public List<Map<String, Object>> getAllSchemas(String protocol) {
        boolean openai = "openai".equals(protocol) || "openai-compat".equals(protocol);
        var schemas = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            // 每个工具自己实现 schema()，返回 {name, description, input_schema}
            var base = tool.schema();
            if (openai) {
                schemas.add(Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", base.get("name"),
                                "description", base.get("description"),
                                "parameters", base.get("input_schema")
                        )
                ));
            } else {
                schemas.add(base);
            }
        }
        return schemas;
    }

    /** 创建默认注册中心：注册六个内置工具，并共享同一个文件状态缓存。 */
    public static ToolRegistry createDefault() {
        return createDefault(new FileStateCache());
    }

    /** 创建默认注册中心，可注入自定义的文件状态缓存。 */
    public static ToolRegistry createDefault(FileStateCache cache) {
        var reg = new ToolRegistry();
        var read = new ReadFileTool();
        read.setFileStateCache(cache);
        var write = new WriteFileTool();
        write.setFileStateCache(cache);
        var edit = new EditFileTool();
        edit.setFileStateCache(cache);
        reg.register(read);
        reg.register(write);
        reg.register(edit);
        reg.register(new BashTool());
        reg.register(new GlobTool());
        reg.register(new GrepTool());
        return reg;
    }
}
