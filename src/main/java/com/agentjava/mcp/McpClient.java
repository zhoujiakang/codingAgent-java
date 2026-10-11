package com.agentjava.mcp;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个 MCP Server 的连接客户端：负责协议协商、工具发现、工具调用、请求-响应异步匹配。
 *
 * <p>角色定位：本 Agent 是 Host，本类就是 Host 内部对应某一个 Server 的 Client。每个请求都是
 * 独立的（无状态），但连接本身有生命周期，所以一个 Server 一个 Client。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>版本协商</b>：先发 {@code server/discover} 探测现代协议（2026-07-28）；若服务器不认识
 *       或只列旧版本，则回退到 {@code initialize} 握手（2025-11-25）。旧协议 Server 可能干脆不回，
 *       故探测只等 10 秒。</li>
 *   <li><b>每条请求自带 _meta</b>：现代协议下，每个请求 params 都带 protocolVersion / clientInfo，
 *       服务器无需记忆客户端（无状态）。旧协议则不带。</li>
 *   <li><b>异步匹配</b>：用一个 pending map 按 id 把响应派发回对应请求；一个守护读线程持续从
 *       传输读消息，响应按 id 唤醒等待的请求，通知（无 id）直接忽略。</li>
 *   <li><b>回退换传输</b>：HTTP 在收到首个响应后会锁死会话 ID，回退握手前必须换一个干净传输；
 *       stdio 同一个子进程即可接着握手，绝不重启进程。</li>
 * </ul>
 */
public class McpClient {

    static final String MODERN_PROTOCOL_VERSION = "2026-07-28";
    static final String LEGACY_PROTOCOL_VERSION = "2025-11-25";
    static final String METHOD_DISCOVER = "server/discover";
    static final String METHOD_INITIALIZE = "initialize";
    static final String METHOD_INITIALIZED = "notifications/initialized";
    static final String METHOD_LIST = "tools/list";
    static final String METHOD_CALL = "tools/call";

    static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    static final String CLIENT_NAME = "AgentJava";
    static final String CLIENT_VERSION = "0.1.0";

    static final Duration DISCOVER_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final List<String> LEGACY_VERSIONS = List.of(
            "2024-11-05", "2024-11-19", "2025-03-26", "2025-06-18", "2025-11-25");

    private final McpServerConfig config;
    private McpTransport transport;
    private volatile Thread reader;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ConcurrentHashMap<Object, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    private volatile String protocolVersion;
    private volatile boolean modern = false;
    private volatile String instructions = "";

    public McpClient(McpServerConfig config) {
        this.config = config;
        this.transport = createTransport(config);
        this.reader = null;
    }

    private McpTransport createTransport(McpServerConfig cfg) {
        if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
            return new StdioTransport(cfg);
        } else if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
            Map<String, String> resolved = new LinkedHashMap<>();
            if (cfg.getHeaders() != null) {
                for (var e : cfg.getHeaders().entrySet()) {
                    resolved.put(e.getKey(), McpToolWrapper.resolveEnvVars(e.getValue()));
                }
            }
            return new HttpTransport(cfg.getUrl(), resolved);
        } else {
            throw new McpException("MCP Server '" + cfg.getName() + "' 缺少 command 或 url");
        }
    }

    /** 建连接：先探测现代协议，失败则回退 initialize 握手。 */
    public void connect() {
        startReader();
        if (tryDiscover()) {
            return;
        }
        if (transport.isHttp()) {
            // HTTP 收到首个响应即锁死会话 ID，回退前换一个干净传输
            stopReaderAndTransport();
            this.transport = createTransport(config);
            startReader();
        }
        initialize();
    }

    private void startReader() {
        final McpTransport t = this.transport;
        reader = new Thread(() -> readLoop(t), "mcp-reader-" + config.getName());
        reader.setDaemon(true);
        reader.start();
    }

    private void stopReaderAndTransport() {
        pending.clear();
        try {
            transport.close();
        } catch (Exception ignored) {
        }
    }

    /** 读循环：持续从传输读消息，按 id 派发到等待的请求；EOF 则结束并清理。 */
    private void readLoop(McpTransport t) {
        try {
            while (alive.get()) {
                String raw;
                try {
                    raw = t.receive();
                } catch (IOException e) {
                    return;
                }
                if (raw == null) {
                    return; // 传输关闭 / EOF
                }
                Map<String, Object> msg;
                try {
                    msg = JsonRpc.deserialize(raw);
                } catch (McpException e) {
                    continue; // 跳过无法解析的行
                }
                Long idKey = toLong(JsonRpc.getId(msg));
                if (idKey != null) {
                    CompletableFuture<Map<String, Object>> f = pending.remove(idKey);
                    if (f != null) {
                        f.complete(msg);
                    }
                }
                // 通知（无 id）忽略
            }
        } finally {
            for (var f : pending.values()) {
                f.completeExceptionally(new McpException("MCP 连接已断开: " + config.getName()));
            }
            pending.clear();
        }
    }

    private static Long toLong(Object id) {
        if (id instanceof Long l) {
            return l;
        }
        if (id instanceof Integer i) {
            return i.longValue();
        }
        if (id instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    /** 探测现代协议：发 server/discover，supportedVersions 含 2026-07-28 才算成功。 */
    private boolean tryDiscover() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("_meta", modernMeta());
        try {
            Map<String, Object> result = sendRequest(METHOD_DISCOVER, params, DISCOVER_TIMEOUT);
            Object versions = result.get("supportedVersions");
            if (!(versions instanceof List<?> list) || !list.contains(MODERN_PROTOCOL_VERSION)) {
                return false;
            }
            modern = true;
            protocolVersion = MODERN_PROTOCOL_VERSION;
            instructions = result.get("instructions") instanceof String s ? s : "";
            return true;
        } catch (Exception e) {
            // 方法不存在、版本不支持、超时都属于「旧协议 Server」信号，回退握手
            return false;
        }
    }

    /** 旧协议握手：initialize + initialized 通知。 */
    private void initialize() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", LEGACY_PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", CLIENT_NAME, "version", CLIENT_VERSION));
        Map<String, Object> result = sendRequest(METHOD_INITIALIZE, params, REQUEST_TIMEOUT);
        Object pv = result.get("protocolVersion");
        if (!(pv instanceof String s) || !LEGACY_VERSIONS.contains(s)) {
            throw new McpException("不支持的协议版本: " + pv);
        }
        protocolVersion = s;
        modern = false;
        instructions = result.get("instructions") instanceof String ins ? ins : "";
        try {
            transport.send(JsonRpc.serialize(JsonRpc.notification(METHOD_INITIALIZED, null)));
        } catch (IOException e) {
            throw new McpException("发送 initialized 通知失败: " + e.getMessage(), e);
        }
    }

    /** 拉取全部工具（带游标分页）。 */
    public List<McpToolDef> listTools() {
        List<McpToolDef> tools = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> params = new LinkedHashMap<>();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            Map<String, Object> meta = requestMeta();
            if (meta != null) {
                params.put("_meta", meta);
            }
            Map<String, Object> result = sendRequest(METHOD_LIST, params, REQUEST_TIMEOUT);
            Object toolsObj = result.get("tools");
            if (toolsObj instanceof List<?> list) {
                for (var item : list) {
                    if (item instanceof Map<?, ?> m) {
                        tools.add(McpToolDef.fromMap(castMap(m)));
                    }
                }
            }
            Object next = result.get("nextCursor");
            cursor = next instanceof String s && !s.isEmpty() ? s : null;
        } while (cursor != null);
        return tools;
    }

    /** 调用工具：返回原始 result（含 content / isError）。 */
    public Map<String, Object> callTool(String name, Map<String, Object> args) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", args == null ? Map.of() : args);
        Map<String, Object> meta = requestMeta();
        if (meta != null) {
            params.put("_meta", meta);
        }
        Map<String, Object> raw = sendRequest(METHOD_CALL, params, REQUEST_TIMEOUT);
        // 本客户端未声明 Elicitation 能力，服务器不应返回 input_required；兜底检查
        Object rt = raw.get("resultType");
        if ("input_required".equals(rt)) {
            throw new McpException("MCP Server 请求客户端补充输入（Elicitation），当前不支持");
        }
        return raw;
    }

    public String getServerName() {
        return config.getName();
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public boolean isModern() {
        return modern;
    }

    public String getInstructions() {
        return instructions;
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            alive.set(false);
            if (reader != null) {
                reader.interrupt();
            }
            try {
                transport.close();
            } catch (Exception ignored) {
            }
        }
    }

    private Map<String, Object> requestMeta() {
        return modern ? modernMeta() : null;
    }

    private Map<String, Object> modernMeta() {
        var meta = new LinkedHashMap<String, Object>();
        meta.put(META_PROTOCOL_VERSION, MODERN_PROTOCOL_VERSION);
        // 本客户端只用 tools，不声明 roots / sampling / elicitation
        meta.put(META_CLIENT_CAPABILITIES, Map.of());
        meta.put(META_CLIENT_INFO, Map.of("name", CLIENT_NAME, "version", CLIENT_VERSION));
        return meta;
    }

    /** 发送请求并阻塞等到响应（或超时）。 */
    private Map<String, Object> sendRequest(String method, Map<String, Object> params, Duration timeout) {
        long id = nextId.getAndIncrement();
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            transport.send(JsonRpc.serialize(JsonRpc.request(id, method, params)));
        } catch (IOException e) {
            pending.remove(id);
            throw new McpException("发送请求失败 (" + method + "): " + e.getMessage(), e);
        }
        try {
            Map<String, Object> resp = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (JsonRpc.hasError(resp)) {
                throw new McpProtocolException(JsonRpc.errorMessage(resp));
            }
            return JsonRpc.resultOf(resp);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new McpException("MCP 请求超时 (" + method + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw new McpException("MCP 请求被中断 (" + method + ")");
        } catch (java.util.concurrent.ExecutionException e) {
            pending.remove(id);
            Throwable cause = e.getCause();
            if (cause instanceof McpException me) {
                throw me;
            }
            throw new McpException("MCP 请求失败 (" + method + "): " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
