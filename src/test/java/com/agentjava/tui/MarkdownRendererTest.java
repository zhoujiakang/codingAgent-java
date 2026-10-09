package com.agentjava.tui;

import com.agentjava.tui.tea.Ansi;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markdown 渲染的测试。
 */
class MarkdownRendererTest {

    @Test
    void 代码块会被渲染成带边框的块() {
        String out = MarkdownRenderer.render("```java\nint a = 1;\n```", 60);
        String plain = Ansi.strip(out);

        assertTrue(plain.contains("int a = 1;"), "代码内容不该丢：" + plain);
        // Mordant 用 box-drawing 字符画代码块边框
        assertTrue(plain.contains("╭") || plain.contains("│"), "代码块应有边框：" + plain);
    }

    @Test
    void 无序列表会被渲染成项目符号() {
        String out = MarkdownRenderer.render("- 第一项\n- 第二项", 60);
        String plain = Ansi.strip(out);

        assertTrue(plain.contains("第一项"));
        assertTrue(plain.contains("第二项"));
        assertTrue(plain.contains("•"), "列表项应带项目符号：" + plain);
    }

    @Test
    void 输出带ANSI颜色() {
        String out = MarkdownRenderer.render("**粗体**", 60);
        assertTrue(out.contains("\u001b["), "应有 ANSI 转义序列：" + out.replace("\u001b", "ESC"));
    }

    @Test
    void 空输入返回空串() {
        assertEquals("", MarkdownRenderer.render("", 60));
        assertEquals("", MarkdownRenderer.render(null, 60));
        assertEquals("", MarkdownRenderer.render("   ", 60));
    }

    @Test
    void 长段落会按宽度折行() {
        String paragraph = "The quick brown fox jumps over the lazy dog and keeps running "
                + "through the field until it reaches the other side of the hill.";
        String out = MarkdownRenderer.render(paragraph, 30);

        String[] lines = Ansi.strip(out).split("\n");
        assertTrue(lines.length > 1, "长段落应该折成多行，实际：" + String.join(" | ", lines));
        for (String line : lines) {
            assertTrue(Ansi.displayWidth(line) <= 34, "折行后单行不该明显超宽：" + line);
        }
    }
}
