package com.agentjava.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Streamable HTTP 传输：把每条 JSON-RPC 消息作为一个 HTTP POST 发给 Server 的固定端点。
 *
 * <p>和 stdio 的区别只在「消息怎么送出去」：这里用 {@link HttpClient} 发 POST，Server 可以直接
 * 回 {@code application/json}，也可以用 SSE（{@code text/event-stream}）流式推结果。两种响应都
 * 拆成一条条原始 JSON 消息投递进接收队列，上层读循环按 id 派发，与 stdio 完全一致。
 *
 * <p>请求头按规范带上（头和请求体各写一份，网关/负载均衡好做路由）：
 * <ul>
 *   <li>{@code Content-Type: application/json}</li>
 *   <li>{@code Accept: application/json, text/event-stream}</li>
 *   <li>{@code MCP-Protocol-Version} / {@code Mcp-Method} / （tools/call 时）{@code Mcp-Name}，
 *       这些都从请求体里的 method、_meta、name 现取，保证头与体一致。</li>
 * </ul>
 *
 * <p>{@code send} 内部完成 POST 并把响应入队；{@code receive} 从队列取一条。这样把「请求-响应」
 * 也统一到了「读循环按 id 匹配」的模型里：主线程 send 入队，读线程 receive 出队派发。
 */
public class HttpTransport implements McpTransport {

    private final String url;
    private final Map<String, String> headers;
    private final HttpClient http;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(64);
    private volatile boolean closed = false;

    public HttpTransport(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers != null ? headers : Map.of();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    @Override
    public void send(String jsonRequest) throws IOException {
        if (closed) {
            throw new IOException("transport 已关闭");
        }
        Map<String, Object> body = JsonRpc.deserialize(jsonRequest);
        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        for (var h : headers.entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }
        // 从请求体取值，头与体各写一份
        Object methodObj = body.get("method");
        if (methodObj instanceof String method) {
            Object params = body.get("params");
            Map<String, Object> paramsMap = params instanceof Map<?, ?> m ? castMap(m) : Map.of();
            Object meta = paramsMap.get("_meta");
            Map<String, Object> metaMap = meta instanceof Map<?, ?> m ? castMap(m) : Map.of();
            Object pv = metaMap.get("io.modelcontextprotocol/protocolVersion");
            if (pv instanceof String version) {
                builder.header("MCP-Protocol-Version", version);
            }
            builder.header("Mcp-Method", method);
            if ("tools/call".equals(method)) {
                Object name = paramsMap.get("name");
                if (name instanceof String n) {
                    builder.header("Mcp-Name", n);
                }
            }
        }

        HttpRequest req;
        try {
            req = builder.POST(HttpRequest.BodyPublishers.ofString(jsonRequest, StandardCharsets.UTF_8)).build();
        } catch (Exception e) {
            throw new IOException("构造 HTTP 请求失败: " + e.getMessage(), e);
        }

        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("MCP HTTP 请求失败: " + e.getMessage(), e);
        }

        String contentType = resp.headers().firstValue("Content-Type").orElse("application/json");
        try (InputStream is = resp.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            if (contentType.contains("text/event-stream")) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("data:")) {
                        String data = trimmed.substring(5).trim();
                        if (!data.isEmpty() && !"[DONE]".equals(data)) {
                            queue.put(data);
                        }
                    }
                }
            } else {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = reader.readLine()) != null) {
                    sb.append(l);
                }
                String payload = sb.toString().trim();
                if (!payload.isEmpty()) {
                    queue.put(payload);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("读取响应被中断", e);
        }
    }

    @Override
    public String receive() {
        try {
            while (!closed) {
                String v = queue.poll(5, TimeUnit.SECONDS);
                if (v != null) {
                    return v;
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public boolean isHttp() {
        return true;
    }

    @Override
    public void close() {
        closed = true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
