package com.agentjava.tui.tea;

/**
 * TUI 事件总线上的消息。
 *
 * 所有驱动界面变化的东西都包成一条消息投给 Program，由它转交给 Model。
 * 这不是 sealed 接口，因为应用层（tui 包）还会有自己的消息类型，
 * 比如流式事件消息。
 */
public interface Message {
}
