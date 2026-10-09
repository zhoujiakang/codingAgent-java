package com.agentjava.tui.tea;

/**
 * 按键消息。
 *
 * @param key  归一化后的按键名，如 "enter"、"ctrl+c"、"up"、"backspace"、"rune"
 * @param rune 按键对应的可见字符；只有 key 为 "rune" 时才有意义
 */
public record KeyPressMessage(String key, String rune) implements Message {

    public static KeyPressMessage of(String key) {
        return new KeyPressMessage(key, "");
    }

    public static KeyPressMessage rune(String text) {
        return new KeyPressMessage("rune", text);
    }
}
