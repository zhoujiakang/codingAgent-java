package com.agentjava.tui;

import com.agentjava.agent.AgentEvent;
import com.agentjava.tui.tea.Message;

/**
 * Agent 事件消息。
 *
 * Agent 跑在虚拟线程里，把每一步进展包成 AgentEvent 投回 TUI 的事件循环，
 * 由 Model 在主线程里统一渲染——状态变更永远只发生在主线程，避免并发踩踏。
 */
public record AgentEventMessage(AgentEvent event) implements Message {
}
