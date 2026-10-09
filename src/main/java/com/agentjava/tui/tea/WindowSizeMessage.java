package com.agentjava.tui.tea;

/**
 * 终端尺寸变化消息。
 *
 * 界面布局要按宽度自适应（N6），所以尺寸一变就得通知 Model 重新排版。
 */
public record WindowSizeMessage(int width, int height) implements Message {
}
