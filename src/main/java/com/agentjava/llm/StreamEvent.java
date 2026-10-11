package com.agentjava.llm;

import java.util.Map;

/**
 * 协议无关的流式事件。
 *
 * 这是封装层的核心：上层只认这套类型，不认识任何 SDK 的类型。
 * 不管底下走的是 Anthropic 还是 OpenAI，吐出来的都是这几种事件，
 * 所以将来换供应商、加新协议，上层代码一行都不用动。
 */
public sealed interface StreamEvent {

    /** 正文文本增量；模型一个词一个词地往外吐，界面就一个词一个词地显示。 */
    record TextDelta(String text) implements StreamEvent {
    }

    /**
     * 扩展思考的增量。
     *
     * 按要求，思考内容接收即丢弃，不渲染、不折叠展示。
     * 保留这个事件类型是为了让上层知道"模型确实在思考"，而不是卡住了。
     */
    record ThinkingDelta(String text) implements StreamEvent {
    }

    /** 本轮响应结束，带上停止原因与 token 用量。 */
    record StreamEnd(String stopReason, int inputTokens, int outputTokens,
                    int cacheReadTokens, int cacheCreationTokens) implements StreamEvent {
    }

    /** 模型开始调用一个工具（拿到工具 id 与名称）。 */
    record ToolCallStart(String toolId, String toolName) implements StreamEvent {
    }

    /** 工具参数的 JSON 碎片（流式分片到达，需拼接后解析）。 */
    record ToolCallDelta(String text) implements StreamEvent {
    }

    /** 一个工具的参数完整解析完毕，可以执行了。 */
    record ToolCallComplete(String toolId, String toolName, Map<String, Object> arguments) implements StreamEvent {
    }

    /** 请求失败；上层展示错误并回到空闲，不退出程序（F11）。 */
    record Error(String message) implements StreamEvent {
    }
}
