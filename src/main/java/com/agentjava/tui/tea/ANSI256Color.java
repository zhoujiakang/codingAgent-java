package com.agentjava.tui.tea;

/**
 * ANSI 256 色与常用属性的转义序列。
 *
 * 不引第三方颜色库，直接拼转义序列，够用也够轻。
 */
public final class ANSI256Color {

    public static final String RESET = "\u001b[0m";
    public static final String BOLD = "\u001b[1m";
    public static final String DIM = "\u001b[2m";
    public static final String ITALIC = "\u001b[3m";
    public static final String UNDERLINE = "\u001b[4m";

    public static final String BOLD_OFF = "\u001b[22m";
    public static final String DIM_OFF = "\u001b[22m";
    public static final String ITALIC_OFF = "\u001b[23m";
    public static final String UNDERLINE_OFF = "\u001b[24m";

    // 常用色号（256 色表里的位置）
    public static final int BLACK = 0;
    public static final int RED = 1;
    public static final int GREEN = 2;
    public static final int YELLOW = 3;
    public static final int BLUE = 4;
    public static final int MAGENTA = 5;
    public static final int CYAN = 6;
    public static final int WHITE = 7;
    public static final int BRIGHT_BLACK = 8;
    public static final int BRIGHT_RED = 9;
    public static final int BRIGHT_GREEN = 10;
    public static final int BRIGHT_YELLOW = 11;
    public static final int BRIGHT_BLUE = 12;
    public static final int BRIGHT_MAGENTA = 13;
    public static final int BRIGHT_CYAN = 14;
    public static final int BRIGHT_WHITE = 15;

    public static final int GREY = 244;
    public static final int DARK_GREY = 238;
    public static final int ORANGE = 214;
    public static final int PURPLE = 141;
    public static final int TEAL = 79;

    private ANSI256Color() {
    }

    /** 前景色。 */
    public static String fg(int code) {
        return "\u001b[38;5;" + code + "m";
    }

    /** 背景色。 */
    public static String bg(int code) {
        return "\u001b[48;5;" + code + "m";
    }
}
