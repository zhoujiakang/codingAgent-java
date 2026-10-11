import com.agentjava.mcp.McpManager;
import com.agentjava.mcp.McpServerConfig;
import com.agentjava.mcp.McpToolWrapper;
import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolRegistry;
import com.agentjava.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 离线验证：不依赖任何外部 MCP Server，用本地假 Server 驱动完整协议流程。
 *
 * 两种运行形态：
 *   java VerifyMcpClient server <modern|legacy>   作为 stdio 假 Server 运行（被测试进程拉起）
 *   gradle test --tests VerifyMcpClient           运行测试套件（默认）
 */
public class VerifyMcpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static int passed = 0;
    private static int failed = 0;

    static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "server".equals(args[0])) {
            runFakeServer("modern".equals(args.length > 1 ? args[1] : "modern"));
            return;
        }
        runTests();
    }

    @Test
    public void protocolFlow() throws Exception {
        passed = 0;
        failed = 0;
        runTests();
        assertEquals(0, failed, "验证失败数: " + failed);
    }

    // ───────────────────────── 假 Server（stdio 模式） ─────────────────────────

    private static void runFakeServer(boolean modern) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            Map<String, Object> req = MAPPER.readValue(line, Map.class);
            Object id = req.get("id");
            String method = (String) req.get("method");
            if (id == null) {
                continue; // 通知不回复
            }
            Map<String, Object> resp = handle(modern, method, req, id);
            if (resp != null) {
                out.write(MAPPER.writeValueAsString(resp));
                out.write("\n");
                out.flush();
            }
        }
    }

    private static Map<String, Object> handle(boolean modern, String method, Map<String, Object> req, Object id) {
        return switch (method) {
            case "server/discover" -> modern
                    ? ok(id, Map.of(
                    "resultType", "complete",
                    "supportedVersions", List.of("2026-07-28"),
                    "instructions", "modern fake server",
                    "capabilities", Map.of("tools", Map.of()),
                    "ttlMs", 3600000,
                    "cacheScope", "public"))
                    : err(id, -32601, "Method not found");
            case "initialize" -> ok(id, Map.of(
                    "protocolVersion", "2025-11-25",
                    "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", "fake-legacy", "version", "1.0.0"),
                    "instructions", "legacy fake server"));
            case "tools/list" -> ok(id, Map.of(
                    "resultType", "complete",
                    "tools", List.of(
                            Map.of("name", "echo_tool", "description", "回声工具",
                                    "inputSchema", Map.of("type", "object",
                                            "properties", Map.of("text", Map.of("type", "string")),
                                            "required", List.of("text"))),
                            Map.of("name", "env_echo", "description", "读环境变量",
                                    "inputSchema", Map.of("type", "object",
                                            "properties", Map.of("var", Map.of("type", "string")),
                                            "required", List.of("var")))),
                    "ttlMs", 300000,
                    "cacheScope", "private"));
            case "tools/call" -> {
                Map<String, Object> params = asMap(req.get("params"));
                Map<String, Object> arguments = asMap(params.get("arguments"));
                String name = (String) params.get("name");
                if ("echo_tool".equals(name)) {
                    yield ok(id, Map.of("resultType", "complete",
                            "content", List.of(Map.of("type", "text",
                                    "text", "echo:" + arguments.get("text")))));
                } else if ("env_echo".equals(name)) {
                    String var = (String) arguments.get("var");
                    yield ok(id, Map.of("resultType", "complete",
                            "content", List.of(Map.of("type", "text",
                                    "text", "env:" + System.getenv(var)))));
                }
                yield err(id, -32602, "unknown tool");
            }
            default -> err(id, -32601, "Method not found");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map<?, ?> mm) ? (Map<String, Object>) mm : Map.of();
    }

    private static Map<String, Object> ok(Object id, Map<String, Object> result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> err(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return m;
    }

    // ───────────────────────── 测试套件 ─────────────────────────

    private static void runTests() throws Exception {
        System.out.println("== MCP 协议离线验证 ==");

        String cp = System.getProperty("verify.cp");
        if (cp == null || cp.isBlank()) {
            cp = System.getProperty("java.class.path");
        }
        String java = System.getProperty("java.home") + "/bin/java";

        // 1) 单元测试：命名清洗与拼接
        check("sanitizeName 清洗非字母数字",
                McpToolWrapper.sanitizeName("foo-bar.baz/qux").equals("foo_bar_baz_qux"));
        check("buildMcpToolName 双下划线格式",
                McpToolWrapper.buildMcpToolName("github", "search_issues").equals("mcp__github__search_issues"));

        // 2) stdio 现代协议 Server
        McpServerConfig modernCfg = new McpServerConfig();
        modernCfg.setName("modern");
        modernCfg.setCommand(java);
        modernCfg.setArgs(List.of("-cp", cp, "VerifyMcpClient", "server", "modern"));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("INJECTED", "${SOME_PARENT_ENV}");
        modernCfg.setEnv(env);

        // 3) stdio 旧协议 Server（回退到 initialize）
        McpServerConfig legacyCfg = new McpServerConfig();
        legacyCfg.setName("legacy");
        legacyCfg.setCommand(java);
        legacyCfg.setArgs(List.of("-cp", cp, "VerifyMcpClient", "server", "legacy"));

        ToolRegistry registry = ToolRegistry.createDefault();
        McpManager mgr = new McpManager(List.of(modernCfg, legacyCfg));
        var result = mgr.registerAllTools(registry);

        check("两个 stdio Server 都连上", result.serverCount() == 2 && result.errors().isEmpty());
        check("注册了 4 个 MCP 工具", result.tools().size() == 4);

        Tool echo = registry.get("mcp__modern__echo_tool");
        check("现代协议 Server 工具名格式正确", echo != null);
        ToolResult er = echo.execute(Map.of("text", "hello"));
        check("现代 Server 工具调用返回预期文本", !er.isError() && er.output().equals("echo:hello"));

        Tool envTool = registry.get("mcp__modern__env_echo");
        ToolResult envr = envTool.execute(Map.of("var", "INJECTED"));
        String parentVal = System.getenv("SOME_PARENT_ENV");
        check("${VAR} 环境变量展开 + 隔离透传", !envr.isError() && envr.output().equals("env:" + parentVal));

        Tool legacyEcho = registry.get("mcp__legacy__echo_tool");
        check("旧协议回退 Server 工具可用", legacyEcho != null);
        ToolResult ler = legacyEcho.execute(Map.of("text", "hi"));
        check("旧协议 Server 工具调用返回预期文本", !ler.isError() && ler.output().equals("echo:hi"));

        check("现代与旧协议均成功发现工具",
                registry.get("mcp__modern__echo_tool") != null
                        && registry.get("mcp__legacy__echo_tool") != null);

        mgr.shutdown();

        // 4) Streamable HTTP Server
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger(0);
        httpServer.createContext("/mcp", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> req = MAPPER.readValue(body, Map.class);
                Object id = req.get("id");
                String method = (String) req.get("method");
                Map<String, Object> resp = handle(true, method, req, id);
                calls.incrementAndGet();
                byte[] bytes = MAPPER.writeValueAsString(resp).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
        int port = httpServer.getAddress().getPort();
        String url = "http://127.0.0.1:" + port + "/mcp";

        McpServerConfig httpCfg = new McpServerConfig();
        httpCfg.setName("remote");
        httpCfg.setUrl(url);
        ToolRegistry reg2 = ToolRegistry.createDefault();
        McpManager mgr2 = new McpManager(List.of(httpCfg));
        var r2 = mgr2.registerAllTools(reg2);
        check("HTTP Server 连上", r2.serverCount() == 1 && r2.errors().isEmpty());
        Tool httpEcho = reg2.get("mcp__remote__echo_tool");
        check("HTTP 工具名格式正确", httpEcho != null);
        ToolResult her = httpEcho.execute(Map.of("text", "web"));
        check("HTTP 工具调用返回预期文本", !her.isError() && her.output().equals("echo:web"));
        mgr2.shutdown();
        httpServer.stop(0);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
    }
}
