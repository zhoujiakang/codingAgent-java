package com.agentjava.tui;

import com.agentjava.tui.tea.ANSI256Color;
import com.agentjava.tui.tea.Style;

/**
 * 全局样式常量。
 *
 * 颜色集中在一处定义，界面各处只引用语义名（PROMPT / MUTED / ERROR…），
 * 想换配色改这里就行。
 */
public final class Styles {

    /** ❯ 提示符与强调色。 */
    public static final Style PROMPT = Style.of().fg(ANSI256Color.ORANGE).bold();

    /** 用户输入。 */
    public static final Style USER = Style.of().fg(ANSI256Color.BRIGHT_WHITE).bold();

    /** 分隔线、边框这类结构线。 */
    public static final Style BORDER = Style.of().fg(ANSI256Color.DARK_GREY);

    /** 次要信息（耗时、token、居中提示）。 */
    public static final Style MUTED = Style.of().fg(ANSI256Color.GREY);

    /** 输入框占位文字。 */
    public static final Style PLACEHOLDER = Style.of().fg(ANSI256Color.DARK_GREY);

    /** spinner 与"处理中"指示。 */
    public static final Style SPINNER = Style.of().fg(ANSI256Color.PURPLE);

    /** 错误信息，要和正常回复明显区分开（F11）。 */
    public static final Style ERROR = Style.of().fg(ANSI256Color.BRIGHT_RED);

    /** 选中项、就绪提示这类正向强调。 */
    public static final Style ACCENT = Style.of().fg(ANSI256Color.TEAL);

    /** 工具调用与结果摘要的展示。 */
    public static final Style TOOL = Style.of().fg(ANSI256Color.GREEN).bold();

    private Styles() {
    }
}
