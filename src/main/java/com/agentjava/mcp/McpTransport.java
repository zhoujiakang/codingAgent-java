package com.agentjava.mcp;

import java.io.IOException;

/**
 * 传输层抽象：只负责「消息的收发」，不关心上层是版本协商、tools/list 还是 tools/call。
 *
 * <p>这是接入 MCP 的关键边界——stdio 走子进程管道、Streamable HTTP 走 POST，对上层完全透明。
 * 两种实现都满足同一套契约：
 * <ul>
 *   <li>{@link #send(String)}：发一条已序列化的 JSON-RPC 消息。</li>
 *   <li>{@link #receive()}：阻塞等到下一条原始消息；EOF/关闭时返回 null。</li>
 * </ul>
 *
 * <p>stdio 的 {@code send} 直接写子进程 stdin；HTTP 的 {@code send} 内部完成 POST 并把响应
 * 投递进接收队列，由 {@code receive} 逐条取出。上层用一个读循环按 {@code id} 把响应派发回
 * 对应请求，两种传输共用同一套逻辑。
 */
public interface McpTransport extends AutoCloseable {

    /** 发送一条已序列化的 JSON-RPC 消息。 */
    void send(String jsonRequest) throws IOException;

    /** 阻塞等到下一条原始消息；EOF 或传输关闭时返回 null。 */
    String receive() throws IOException;

    /** 是否为 Streamable HTTP 传输（回退握手前需要换新传输）。 */
    boolean isHttp();

    @Override
    void close() throws IOException;
}
