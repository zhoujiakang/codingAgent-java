package com.agentjava.mcp;

/**
 * 协议层错误：服务器回了 JSON-RPC error，或明确拒绝了本次请求。
 *
 * <p>和 {@link McpException} 的区别：这类错误通常意味着「服务器活着，但不支持某件事」，
 * 例如旧协议 Server 不认识 {@code server/discover} 而回方法不存在。{@code McpClient}
 * 在探测阶段捕获它就会回退到 {@code initialize} 握手，而不是直接放弃连接。
 */
public class McpProtocolException extends McpException {

    public McpProtocolException(String message) {
        super(message);
    }
}
