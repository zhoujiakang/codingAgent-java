package com.agentjava.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * stdio 传输：把 MCP Server 作为本地子进程启动，通过它的 stdin/stdout 管道收发消息。
 *
 * <p>特点：
 * <ul>
 *   <li>不需要网络、端口、服务发现——进程起来即可用，退出即清理。</li>
 *   <li>消息是 UTF-8 的 JSON-RPC，以换行分隔；Server 的 <b>stderr 不参与协议</b>，
 *       这里单独起一个守护线程把它丢掉，避免日志撑满缓冲区把子进程卡死。</li>
 *   <li><b>环境变量隔离</b>：子进程只拿到 {@code PATH} 加配置里显式声明的变量，
 *       不继承父进程其余环境，降低敏感信息泄漏风险（${VAR} 在赋值前先展开）。</li>
 * </ul>
 */
public class StdioTransport implements McpTransport {

    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public StdioTransport(McpServerConfig cfg) {
        try {
            var pb = new ProcessBuilder();
            List<String> cmd = new ArrayList<>();
            cmd.add(McpToolWrapper.windowsSafe(cfg.getCommand()));
            if (cfg.getArgs() != null) {
                cmd.addAll(cfg.getArgs());
            }
            pb.command(cmd);

            // 环境变量隔离：先清空，只放回 PATH，再补配置显式声明的变量
            Map<String, String> env = pb.environment();
            env.clear();
            String path = System.getenv("PATH");
            if (path != null) {
                env.put("PATH", path);
            }
            if (cfg.getEnv() != null) {
                for (var e : cfg.getEnv().entrySet()) {
                    env.put(e.getKey(), McpToolWrapper.resolveEnvVars(e.getValue()));
                }
            }

            this.process = pb.start();
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            // 单独线程消费 stderr，协议只走 stdout
            var stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
            Thread errDrain = new Thread(() -> {
                try {
                    String line;
                    while ((line = stderrReader.readLine()) != null) {
                        // 丢弃：stderr 不参与协议通信
                    }
                } catch (IOException ignored) {
                }
            }, "mcp-stderr-" + cfg.getName());
            errDrain.setDaemon(true);
            errDrain.start();
        } catch (IOException e) {
            throw new McpException("启动 MCP Server 子进程失败: " + cfg.getName() + " (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public void send(String jsonRequest) throws IOException {
        if (closed.get()) {
            throw new IOException("transport 已关闭");
        }
        synchronized (stdin) {
            stdin.write(jsonRequest);
            stdin.write("\n");
            stdin.flush();
        }
    }

    @Override
    public String receive() throws IOException {
        if (closed.get()) {
            return null;
        }
        return stdout.readLine();
    }

    @Override
    public boolean isHttp() {
        return false;
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            try {
                stdin.close();
            } catch (IOException ignored) {
            }
            try {
                stdout.close();
            } catch (IOException ignored) {
            }
            process.destroyForcibly();
        }
    }
}
