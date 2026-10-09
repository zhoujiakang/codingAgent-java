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

    /** 快捷构造：批量。 */
    static Command batch(Command... commands) {
        return new Batch(List.of(commands));
    }
}
