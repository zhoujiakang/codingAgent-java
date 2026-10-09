package com.agentjava.tui.tea;

/**
 * 终端文本样式。
 *
 * 只是把 ANSI256Color 里的转义序列按属性拼起来，
 * 负责在渲染时统一"开样式 → 文本 → 关样式"，避免颜色漏到后面的内容上。
 */
public final class Style {

    private Integer fg;
    private Integer bg;
    private boolean bold;
    private boolean dim;
    private boolean italic;
    private boolean underline;

    public static Style of() {
        return new Style();
    }

    public Style fg(int code) {
        this.fg = code;
        return this;
    }

    public Style bg(int code) {
        this.bg = code;
        return this;
    }

    public Style bold() {
        this.bold = true;
        return this;
    }

    public Style dim() {
        this.dim = true;
        return this;
    }

    public Style italic() {
        this.italic = true;
        return this;
    }

    public Style underline() {
        this.underline = true;
        return this;
    }

    /** 样式开头（不含文本）。 */
    public String open() {
        StringBuilder sb = new StringBuilder();
        if (fg != null) {
            sb.append(ANSI256Color.fg(fg));
        }
        if (bg != null) {
            sb.append(ANSI256Color.bg(bg));
        }
        if (bold) {
            sb.append(ANSI256Color.BOLD);
        }
        if (dim) {
            sb.append(ANSI256Color.DIM);
        }
        if (italic) {
            sb.append(ANSI256Color.ITALIC);
        }
        if (underline) {
            sb.append(ANSI256Color.UNDERLINE);
        }
        return sb.toString();
    }

    /** 样式结尾，逐项关掉，避免影响后续输出。 */
    public String close() {
        StringBuilder sb = new StringBuilder();
        if (underline) {
            sb.append(ANSI256Color.UNDERLINE_OFF);
        }
        if (italic) {
            sb.append(ANSI256Color.ITALIC_OFF);
        }
        if (dim || bold) {
            sb.append(ANSI256Color.BOLD_OFF);
        }
        sb.append(ANSI256Color.RESET);
        return sb.toString();
    }

    /** 给一段文本套上样式。 */
    public String render(String text) {
        return open() + text + close();
    }
}
