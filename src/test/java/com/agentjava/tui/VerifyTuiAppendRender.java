package com.agentjava.tui;

import com.agentjava.tui.tea.Ansi;
import com.agentjava.tui.tea.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 追加式渲染的测试。
 *
 * <p>流式正文从「塞进帧、每帧整帧擦除重画」改成「直接追加进终端历史」之后，
 * 折行必须自己做（终端自动折行会让"当前列数"失控，临时状态行就退不回正文落笔点）。
 * 这里把折行的边界条件钉死——它是这套方案里唯一容易算错的地方。
 */
class VerifyTuiAppendRender {

    /** 只用到 width-1 列，避免正好写满最后一列触发终端自动折行。 */
    private static final int W = 40;

    @Test
    @DisplayName("短片段不折行，列数按可见宽度累加")
    void 短片段不折行() {
        var r = AgentModel.wrapStream("hello", 0, W);
        assertEquals("hello", r.text());
        assertEquals(5, r.column());
    }

    @Test
    @DisplayName("空片段原样返回，列数不变")
    void 空片段() {
        assertEquals("", AgentModel.wrapStream("", 7, W).text());
        assertEquals(7, AgentModel.wrapStream("", 7, W).column());
        assertEquals("", AgentModel.wrapStream(null, 3, W).text());
        assertEquals(3, AgentModel.wrapStream(null, 3, W).column());
    }

    @Test
    @DisplayName("片段内的换行原样透传，并把列数归零")
    void 换行透传并归零() {
        var r = AgentModel.wrapStream("ab\ncd", 0, W);
        assertEquals("ab\ncd", r.text());
        assertEquals(2, r.column(), "换行后重新从第 2 列开始");
    }

    @Test
    @DisplayName("CJK 记 2 列：宽度按全角算，不按字符数算")
    void 全角字符按两列计() {
        var r = AgentModel.wrapStream("中文", 0, W);
        assertEquals("中文", r.text());
        assertEquals(4, r.column(), "两个汉字占 4 列");
    }

    @Test
    @DisplayName("跨片段有状态：续写时用的是上一片段留下的列数")
    void 跨片段状态延续() {
        // 第一片段写到第 38 列（W-1=39 是上限，再放一个汉字会超）
        var first = AgentModel.wrapStream("a".repeat(38), 0, W);
        assertEquals(38, first.column());

        // 第二片段：38 + 2 = 40 > 39，必须先折行再写
        var second = AgentModel.wrapStream("中", first.column(), W);
        assertEquals("\n中", second.text(), "应先在上一行末尾换行");
        assertEquals(2, second.column());
    }

    @Test
    @DisplayName("任何一行都不会写到 width 列——那是终端自动折行的触发点")
    void 不写满最后一列() {
        // 连续喂 200 个汉字，检查每一行的可见宽度都 <= W-1
        int col = 0;
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            var r = AgentModel.wrapStream("中", col, W);
            all.append(r.text());
            col = r.column();
            assertTrue(col <= W - 1, "列数越界：" + col);
        }
        for (String line : all.toString().split("\n", -1)) {
            assertTrue(Ansi.displayWidth(line) <= W - 1,
                    "行宽越界：" + Ansi.displayWidth(line) + " / " + W);
        }
    }

    @Test
    @DisplayName("逐字喂入与一次性喂入，折行结果完全一致")
    void 逐字与整体一致() {
        String text = "这是一段用来验证折行一致性的中文文本，混排 abcdefg 与标点，看看会不会错位。";

        // 整体
        String whole = AgentModel.wrapStream(text, 0, W).text();

        // 逐字（模拟流式）
        StringBuilder streamed = new StringBuilder();
        int col = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String one = new String(Character.toChars(cp));
            var r = AgentModel.wrapStream(one, col, W);
            streamed.append(r.text());
            col = r.column();
            i += Character.charCount(cp);
        }

        assertEquals(whole, streamed.toString(), "流式折行结果必须与整段折行一致");
    }

    @Test
    @DisplayName("宽度下限保护：极窄终端兜底到 20 列，8 列内容不会被折行")
    void 极窄终端兜底() {
        // 实现里取 Math.max(20, width) 作下限，所以 width=5 时实际按 20 列算
        var r = AgentModel.wrapStream("中文中文", 0, 5);
        assertEquals("中文中文", r.text(), "兜底宽度下不该折行");
        assertEquals(8, r.column());
    }

    @Test
    @DisplayName("追加指令是独立类型：不会走 printLine 那条撤帧路径")
    void 追加指令类型() {
        Command c = Command.append("abc");
        assertInstanceOf(Command.AppendText.class, c);
        assertEquals("abc", ((Command.AppendText) c).text());
    }

    @Test
    @DisplayName("charWidth：ASCII 1 列，CJK 2 列")
    void 单码位宽度() {
        assertEquals(1, Ansi.charWidth('a'));
        assertEquals(1, Ansi.charWidth(' '));
        assertEquals(2, Ansi.charWidth('中'));
        assertEquals(2, Ansi.charWidth('，'));
    }
}
