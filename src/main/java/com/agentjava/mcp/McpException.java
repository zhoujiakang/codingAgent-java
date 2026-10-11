package com.agentjava.mcp;

/**
 * MCP 模块的根异常：连接、传输、协议层的错误统一收敛到这一类。
 *
 * <p>子类 {@link McpProtocolException} 专门表示「服务器回了 JSON-RPC 错误」这类
 * 协议层问题（如方法不存在、版本不支持），与传输层故障（网络不通、进程起不来）区分开，
 * 方便上层决定是回退握手还是直接报错。
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
