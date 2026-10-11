package com.agentjava.session;

import java.util.List;

/**
 * 会话存档里的一条消息。
 *
 * <p>{@code role}/{@code content}/{@code timestamp} 是骨架，{@code type} 只在压缩边界
 * 记录上填值，工具块以 {@code toolUses} 和 {@code toolResults} 两个列表挂在消息上。
 * 注意工具结果不是独立的 role，它挂在 user 记录上，正文为空字符串——
 * 这是为了满足 API 的 user/assistant 交替规则。
 *
 * <p>紧凑构造器（没有参数列表的那一段）在这里干了一件关键的事：把 null 归一成空列表。
 * 落盘时空列表会被省略，反序列化回来拿到的就是 null，如果不归一，
 * 回放消息时对着 {@code toolUses()} 做 for 循环就会空指针。
 * record 的紧凑构造器可以给形参重新赋值，赋完由编译器自动写进字段，
 * 这是它区别于普通构造器的地方，也是做这类归一化最省事的位置。
 */
public record SessionMessage(String role, String type, String content, long timestamp,
                             List<ToolUseRecord> toolUses, List<ToolResultRecord> toolResults) {

    /** 压缩边界记录的 type 取值。 */
    public static final String TYPE_COMPACT_BOUNDARY = "compact_boundary";

    public SessionMessage {
        toolUses = toolUses == null ? List.of() : toolUses;
        toolResults = toolResults == null ? List.of() : toolResults;
    }

    /** 普通消息的便捷构造：只传角色、正文和时间戳。 */
    public SessionMessage(String role, String content, long timestamp) {
        this(role, null, content, timestamp, List.of(), List.of());
    }

    /** 带 type 但无工具块的便捷构造（压缩边界走这条）。 */
    public SessionMessage(String role, String type, String content, long timestamp) {
        this(role, type, content, timestamp, List.of(), List.of());
    }

    /** 本条是否为压缩边界。 */
    public boolean isCompactBoundary() {
        return TYPE_COMPACT_BOUNDARY.equals(type);
    }
}
