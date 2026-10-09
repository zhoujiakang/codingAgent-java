package com.agentjava.llm;

/**
 * LLM 调用异常。
 *
 * 配置错误的 provider 在构造客户端时就会抛这个，
 * 入口层捕获后转成一句可读提示，而不是让用户看异常栈。
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 密钥缺失或不合法。 */
    public static class AuthenticationException extends LlmException {
        public AuthenticationException(String message) {
            super(message);
        }
    }
}
