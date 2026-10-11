package com.agentjava.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 落盘形态的工具调用记录。
 *
 * <p>Java 字段是驼峰，JSON 键要求下划线，所以每个字段都显式挂 {@code @JsonProperty}。
 * 两个关键注解的选择不一样：
 * <ul>
 *   <li>{@code arguments} 是引用类型，用 {@code NON_EMPTY}，空 map 就不写；</li>
 *   <li>工具 Id 与名称则始终写入——它们是与 {@link ToolResultRecord} 配对的唯一凭据。</li>
 * </ul>
 *
 * <p>{@code arguments} 用 {@code Map<String, Object>} 而不是原始 JSON 字符串，
 * 因为存的是与协议无关的内部表示：恢复时读出来的 map 直接交还给模型适配层，
 * 由它拼成当前 provider 要的线格式。
 */
public record ToolUseRecord(
        @JsonProperty("tool_use_id") String toolUseId,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("arguments") @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> arguments) {
}
