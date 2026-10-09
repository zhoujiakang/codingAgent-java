package com.agentjava.tui.tea;

/**
 * 退出消息。
 *
 * 谁想让程序停下来，就往事件队列里丢一条这个，
 * Program 会在处理完当前帧后干净退出（恢复终端、输出历史）。
 */
public record QuitMessage() implements Message {
}
