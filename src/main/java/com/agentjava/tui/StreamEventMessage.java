package com.agentjava.tui;

import com.agentjava.llm.StreamEvent;
import com.agentjava.tui.tea.Message;

/**
 * 流式事件消息。
 *
 * 网络请求跑在 virtual thread 上，拿到事件后包成这条消息投回事件循环，
 * 由 Model 在主线程里统一处理——这样状态变更永远只发生在一个线程上。
 */
public record StreamEventMessage(StreamEvent event) implements Message {
}
