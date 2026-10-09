package com.agentjava.tui;

import com.github.ajalt.mordant.rendering.AnsiLevel;
import com.github.ajalt.mordant.rendering.Theme;
import com.github.ajalt.mordant.terminal.StandardTerminalInterface;
import com.github.ajalt.mordant.terminal.Terminal;

/**
 * Markdown 渲染。
 *
 * 流式期间显示纯文本（逐字蹦出来才有"实时打字"的观感），
 * 等这一轮说完了，再把整段用 Markdown 重新渲染一遍定型展示（F8）。
 *
 * 底层用 Mordant：它直接吐带 ANSI 的富文本，省得自己把 AST 转成终端样式。
 */
public final class MarkdownRenderer {

    /** 渲染宽度下限；太窄了 Mordant 排版会很难看。 */
    private static final int MIN_WIDTH = 30;

    private MarkdownRenderer() {
    }

    /**
     * 把 Markdown 渲染成带 ANSI 样式的文本。
     *
     * @param markdown 原始 Markdown
     * @param width    渲染宽度（一般取终端宽度）
     * @return 带颜色与边框的文本
     */
    public static String render(String markdown, int width) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        int w = Math.max(MIN_WIDTH, width);
        try {
            Theme theme = Theme.Companion.getDefault();
            // 宽度由外面传进来，所以这里不需要真的去探测终端尺寸
            Terminal terminal = new Terminal(
                    AnsiLevel.TRUECOLOR, theme, w, null, null, null,
                    false, 4, false,
                    new StandardTerminalInterface() {
                    });
            String rendered = terminal.render(
                    new com.github.ajalt.mordant.markdown.MarkdownRenderer(markdown, theme, false, false).render());
            // Mordant 输出首尾常带空行，去掉再交给界面排版
            return rendered.replaceAll("(?m)^\\s*$", "").stripTrailing();
        } catch (Exception e) {
            // 渲染失败不值得中断对话，退回原始文本
            return markdown;
        }
    }
}
