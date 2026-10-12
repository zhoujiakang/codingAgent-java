package com.agentjava.tui.tea;

import java.util.List;

/**
 * update() 的副作用指令。
 *
 * Model 是纯函数式的：update 接收消息、返回新状态，自己不碰终端。
 * 需要"往终端写点东西"或"退出"这类副作用时，就返回一个 Command，
 * 由 Program 代为执行。
 */
public sealed interface Command {

    /** 什么都不做。 */
    record None() implements Command {
    }

    /** 退出程序。 */
    record Quit() implements Command {
    }

    /**
     * 把一段文本写进终端 scrollback。
     *
     * 这是 Claude Code 那种效果的关键：已经说完的内容要脱离"当前帧"，
     * 真正落到终端历史里，用户才能用鼠标滚轮往回翻。
     */
    record PrintLine(String text) implements Command {
    }

    /**
     * 追加一段文本到当前光标处，<b>不</b>擦除任何已有内容。
     *
     * <p>流式正文走这条指令。它的存在是「不重渲染」方案的核心：
     * 正文已经落到终端历史里就改不回去了，所以模型一个字一个词地往外吐时，
     * 直接把字写到光标处即可，没必要擦掉整帧再重画一遍。
     *
     * <p>与 {@link PrintLine} 的区别只有一个：{@code PrintLine} 会先把当前帧撤掉
     * 再把文本写进历史；{@code AppendText} 什么都不撤，就地续写。
     */
    record AppendText(String text) implements Command {
    }

    /** 一次执行多条指令。 */
    record Batch(List<Command> commands) implements Command {
    }

    /** 快捷构造：什么都不做。 */
    static Command none() {
        return new None();
    }

    /** 快捷构造：退出。 */
    static Command quit() {
        return new Quit();
    }

    /** 快捷构造：写入 scrollback。 */
    static Command println(String text) {
        return new PrintLine(text);
    }

    /** 快捷构造：就地追加，不擦除。 */
    static Command append(String text) {
        return new AppendText(text);
    }

    /** 快捷构造：批量。 */
    static Command batch(Command... commands) {
        return new Batch(List.of(commands));
    }
}
