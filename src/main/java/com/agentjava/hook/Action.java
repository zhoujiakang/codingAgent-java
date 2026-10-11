package com.agentjava.hook;

import java.time.Duration;
import java.util.Map;

/**
 * 动作：Hook 触发之后具体做什么。
 *
 * <p>「大 union」设计：不同 {@link ActionType} 只用得到其中一部分字段，用不到的留 null。
 * command 用 {@code command}，prompt 用 {@code message}，http 用
 * {@code url/method/headers/body}，agent 用 {@code message}（{@code command} 作 fallback）。
 *
 * <p>这么设计是为了让 YAML 结构扁平——给每种动作各写一个子类，配置文件就要多一层嵌套，
 * 而 Hook 配置是给人手写的，少一层缩进就少一处出错的地方。
 */
public record Action(
        ActionType type,
        String command,
        String message,
        String url,
        String method,
        Map<String, String> headers,
        String body,
        Duration timeout
) {

    /** 超时未配置时回退到调用方给的默认值。 */
    public Duration timeoutOr(Duration fallback) {
        return timeout == null ? fallback : timeout;
    }

    /** {@code /hooks} 列表里的一行摘要：让人一眼看出这条 Hook 要干什么。 */
    public String summary() {
        if (type == null) {
            return "（未指定 type）";
        }
        return switch (type) {
            case COMMAND -> "command: " + brief(command);
            case PROMPT -> "prompt: " + brief(message);
            case HTTP -> "http: " + brief(url);
            case AGENT -> "agent: " + brief(message != null && !message.isBlank() ? message : command);
        };
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 48 ? oneLine.substring(0, 48) + "…" : oneLine;
    }
}
