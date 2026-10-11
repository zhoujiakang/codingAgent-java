package com.agentjava.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 落盘形态的工具结果记录。
 *
 * <p>{@code isError} 是基本类型 {@code boolean}，没有 null 可言，
 * 所以用 {@code NON_DEFAULT}（而不是 NON_EMPTY）才能让 false 不出现在 JSON 里。
 * 选错注解，落盘结果就会多出一个无意义的 {@code "is_error": false}。
 */
public record ToolResultRecord(
        @JsonProperty("tool_use_id") String toolUseId,
        @JsonProperty("content") String content,
        @JsonProperty("is_error") @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        boolean isError) {
}
