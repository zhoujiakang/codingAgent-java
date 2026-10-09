package com.agentjava.tui.tea;

/**
 * ANSI 转义序列的小工具。
 *
 * 内联渲染要按"行数"精确回退光标，就必须知道一段带颜色的文本在终端里
 * 到底占几个可见字符、会不会折行——转义序列本身是不占宽度的，
 * 中日韩字符又占两格。这三个方法就是为此服务的。
 */
public final class Ansi {

    private Ansi() {
    }

    /** 去掉所有 ANSI 转义序列，只留可见字符。 */
    public static String strip(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\u001b\\[[0-9;]*[A-Za-z]", "");
    }

    /**
     * 计算可见宽度：ASCII 记 1，CJK 及全角标点记 2。
     */
    public static int displayWidth(String text) {
        String plain = strip(text);
        int width = 0;
        for (int i = 0; i < plain.length(); ) {
            int cp = plain.codePointAt(i);
            i += Character.charCount(cp);
            width += isWide(cp) ? 2 : 1;
        }
        return width;
    }

    /** 判断一个码位是不是宽字符（粗略覆盖 CJK 与全角区间）。 */
    private static boolean isWide(int cp) {
        return cp >= 0x1100 && (cp <= 0x115F
                || cp == 0x2329 || cp == 0x232A
                || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE10 && cp <= 0xFE19)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6));
    }

    /**
     * 按可见宽度截断，超出的部分用省略号收尾。
     *
     * 截断时保留原有的转义序列，所以带颜色的长行截短后颜色不会串味。
     */
    public static String truncate(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (maxWidth <= 0) {
            return "";
        }
        if (displayWidth(text) <= maxWidth) {
            return text;
        }

        StringBuilder out = new StringBuilder();
        int width = 0;
        int i = 0;
        while (i < text.length()) {
            // 转义序列原样保留，且不占宽度
            if (text.charAt(i) == '\u001b' && i + 1 < text.length() && text.charAt(i + 1) == '[') {
                int end = text.indexOf('m', i);
                if (end > 0) {
                    out.append(text, i, end + 1);
                    i = end + 1;
                    continue;
                }
            }
            int cp = text.codePointAt(i);
            int step = Character.charCount(cp);
            int w = isWide(cp) ? 2 : 1;
            if (width + w > maxWidth - 1) {
                break;
            }
            out.appendCodePoint(cp);
            width += w;
            i += step;
        }
        out.append("…");
        return out.toString();
    }
}
