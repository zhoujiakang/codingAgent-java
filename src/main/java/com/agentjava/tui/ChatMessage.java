package com.agentjava.tui;

/**
 * 一条要展示的对话消息。
 *
 * 注意它和 conversation 包里的 {@link com.agentjava.conversation.Message} 不是一回事：
 * 那边是"要发给 API 的历史"，这边是"要画到界面上的一行内容"。
 * 分开的好处是渲染想怎么改都不会污染上下文，上下文想压缩也不用管界面。
 *
 * @param kind          消息种类
 * @param text          正文
 * @param elapsedMillis 本轮响应耗时（仅助手消息有意义）
 * @param inputTokens   本轮输入 token（仅助手消息有意义）
 * @param outputTokens  本轮输出 token（仅助手消息有意义）
 */
public record ChatMessage(ChatMessage.Kind kind, String text, long elapsedMillis, int inputTokens, int outputTokens) {

    public enum Kind {
        USER,
        ASSISTANT,
        ERROR
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Kind.USER, text, 0, 0, 0);
    }

    public static ChatMessage assistant(String text, long elapsedMillis, int inputTokens, int outputTokens) {
        return new ChatMessage(Kind.ASSISTANT, text, elapsedMillis, inputTokens, outputTokens);
    }

    public static ChatMessage error(String text) {
        return new ChatMessage(Kind.ERROR, text, 0, 0, 0);
    }
}
