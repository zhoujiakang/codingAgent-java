package com.agentjava.mcp;

import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolCategory;
import com.agentjava.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 适配器：把一个 MCP 工具「包装」成本 Agent 内部的 {@link Tool} 接口。
 *
 * <p>这是让开放生态融入封闭工具集的关键一环——包完之后，MCP 工具和内置工具在 Agent 看来
 * 没有任何区别：都实现 {@code Tool}，都进 {@code ToolRegistry}，都出现在 system prompt 的工具列表。
 *
 * <p>关键约定：
 * <ul>
 *   <li><b>命名隔离</b>：{@code mcp__<server>__<tool>}，双下划线分隔，两段都做非字母数字清洗，
 *       避免不同 Server 同名工具冲突。</li>
 *   <li><b>透传</b>：description / schema 直接透传原始定义；inputSchema 缺省时给最小空对象，
 *       防止模型困惑。</li>
 *   <li><b>桥接执行</b>：execute 调的是<b>原始工具名</b>（{@code def.name()}），不是带前缀的名字——
 *       MCP Server 只认自己定义的名字。</li>
 *   <li><b>分类</b>：归为 COMMAND，默认需要权限确认（权限系统对 MCP 工具同样生效）。</li>
 * </ul>
 *
 * <p>本类同时承载 MCP 模块的公共工具方法（sanitizeName / resolveEnvVars / windowsSafe），
 * 把基础设施收在 wrapper 里，与常见 MCP 适配器的组织方式一致。
 */
public class McpToolWrapper implements Tool {

    /** 工具名前缀：mcp__ */
    public static final String PREFIX = "mcp__";

    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]+");
    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private final String serverName;
    private final McpToolDef def;
    private final McpClient client;

    public McpToolWrapper(String serverName, McpToolDef def, McpClient client) {
        this.serverName = serverName;
        this.def = def;
        this.client = client;
    }

    @Override
    public String name() {
        return buildMcpToolName(serverName, def.name());
    }

    /** mcp__<server>__<tool>，双下划线分隔，两段都清洗。 */
    public static String buildMcpToolName(String serverName, String toolName) {
        return PREFIX + sanitizeName(serverName) + "__" + sanitizeName(toolName);
    }

    /** 非字母数字统一替换成下划线，确保工具名对 LLM 友好。 */
    public static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        return NON_ALNUM.matcher(name).replaceAll("_");
    }

    /** 把 ${VAR} 替换成当前环境变量值，找不到就保留原文（不泄露、不报错）。 */
    static String resolveEnvVars(String value) {
        if (value == null) {
            return null;
        }
        return ENV_VAR.matcher(value).replaceAll(m -> {
            String env = System.getenv(m.group(1));
            return env != null ? env : m.group(0);
        });
    }

    @Override
    public String description() {
        return def.description();
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", inputSchemaMap());
    }

    private Map<String, Object> inputSchemaMap() {
        Map<String, Object> input = new LinkedHashMap<>();
        Map<String, Object> src = def.inputSchema();
        if (src != null) {
            for (var key : List.of("type", "properties", "required")) {
                if (src.get(key) != null) {
                    input.put(key, src.get(key));
                }
            }
        }
        if (input.isEmpty()) {
            input.put("type", "object");
            input.put("properties", Map.of());
        }
        return input;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        try {
            Map<String, Object> result = client.callTool(def.name(), args == null ? Map.of() : args);
            String text = extractTextContent(result);
            boolean isError = result.get("isError") instanceof Boolean b && b;
            return isError ? ToolResult.error(text) : ToolResult.success(text);
        } catch (Exception e) {
            return ToolResult.error("MCP 工具调用失败: " + e.getMessage());
        }
    }

    /** 从 content 数组里抽取所有文本块，拼成一段字符串。 */
    static String extractTextContent(Map<String, Object> result) {
        Object content = result.get("content");
        if (!(content instanceof List<?> list) || list.isEmpty()) {
            return "(no output)";
        }
        var sb = new StringBuilder();
        for (var c : list) {
            if (c instanceof Map<?, ?> m) {
                if ("text".equals(m.get("type")) && m.get("text") instanceof String text) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(text);
                }
            }
        }
        return sb.isEmpty() ? "(no output)" : sb.toString();
    }

    /** Windows 下 npx / npm 等其实是 .cmd 脚本，需补后缀才能正确执行。 */
    static String windowsSafe(String command) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return command;
        }
        String base = command.toLowerCase();
        if (List.of("npx", "npm", "pnpm", "yarn", "bun").contains(base)) {
            return command + ".cmd";
        }
        return command;
    }
}
