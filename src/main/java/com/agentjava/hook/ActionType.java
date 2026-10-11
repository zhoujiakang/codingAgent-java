package com.agentjava.hook;

import java.util.Locale;
import java.util.Optional;

/**
 * 动作类型：Hook 触发之后做什么。
 *
 * <ul>
 *   <li>{@link #COMMAND}：执行 shell 命令（跑格式化、跑测试、跑 lint）；</li>
 *   <li>{@link #PROMPT}：把一段提示词注入对话，下一轮 LLM 就能看到；</li>
 *   <li>{@link #HTTP}：给外部系统发一个请求（通知 Slack、写审计日志）；</li>
 *   <li>{@link #AGENT}：启动子 Agent 处理任务。这里只搭接口与调用骨架，
 *       真实运行时由 SubAgent 对接。</li>
 * </ul>
 */
public enum ActionType {

    COMMAND("command", "执行 shell 命令"),
    PROMPT("prompt", "注入提示词"),
    HTTP("http", "发送 HTTP 请求"),
    AGENT("agent", "启动子 Agent（占位）");

    private final String value;
    private final String description;

    ActionType(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public String value() {
        return value;
    }

    public String description() {
        return description;
    }

    public static Optional<ActionType> fromValue(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        for (ActionType t : values()) {
            if (t.value.equals(s)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
