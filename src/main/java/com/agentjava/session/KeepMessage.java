package com.agentjava.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 压缩边界里「被保留的尾部原文」。
 *
 * <p>压缩会把老的对话换成一段摘要，只留最近一小段原文。这段尾巴如果不带工具块，
 * 恢复出来就会出现「有调用没结果」的断链，所以 {@code KeepMessage} 和
 * {@link SessionMessage} 一样保留 {@code tool_uses} / {@code tool_results} 两个列表，
 * 只是不需要 {@code timestamp} 和 {@code type}。
 *
 * <p>紧凑构造器里同样做了 null 归一，理由和 {@link SessionMessage} 一致：
 * {@code NON_EMPTY} 让空列表不落盘，读回来就是 null。
 */
public record KeepMessage(
        @JsonProperty("role") String role,
        @JsonProperty("content") String content,
        @JsonProperty("tool_uses") @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ToolUseRecord> toolUses,
        @JsonProperty("tool_results") @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ToolResultRecord> toolResults) {

    public KeepMessage {
        toolUses = toolUses == null ? List.of() : toolUses;
        toolResults = toolResults == null ? List.of() : toolResults;
    }
}
