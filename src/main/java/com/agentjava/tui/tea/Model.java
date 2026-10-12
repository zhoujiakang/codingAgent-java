package com.agentjava.tui.tea;

/**
 * TUI 应用模型（Elm 架构里的 Model）。
 *
 * 三个方法：
 * <ul>
 *   <li>{@link #init()} —— 启动时要执行的副作用；</li>
 *   <li>{@link #update(Message)} —— 来一条消息，返回一个新状态；</li>
 *   <li>{@link #view()} —— 把当前状态渲染成一帧文本。</li>
 * </ul>
 *
 * Model 自己不读键盘、不写终端，全靠 Program 喂消息、代执行指令，
 * 所以它非常好测——给它一串消息，看 view() 的输出就行。
 */
public interface Model {

    /** 启动时执行的指令。 */
    Command init();

    /** 处理一条消息，返回新状态与副作用指令。 */
    UpdateResult<? extends Model> update(Message msg);

    /** 渲染当前帧；返回的字符串会被 Program 内联绘制。 */
    String view();

    /**
     * 底部那一行临时状态（转圈 / 耗时），没有就返回 null。
     *
     * <p>它不是帧的一部分：帧是"整块画上去、下一帧整块擦掉"的，
     * 而这一行只在等模型出字的间隙里出现、被原地覆盖重画一行。
     * 之所以单独开一个入口，是因为追加模式下 {@link #view()} 会返回空，
     * 界面完全交给追加指令驱动，再没有"帧"可以承载它。
     *
     * <p>由 Program 在空闲 tick 上拉取，所以它天然只在"没有新内容到达"时刷新——
     * 正文正在往外吐的时候，这一行会让位给正文，不会跟正文抢光标。
     */
    default String statusLine() {
        return null;
    }

    /**
     * 退出时输出的内容。
     *
     * 默认返回空串：每条消息完成时已经写进 scrollback 了，
     * 退出时再重复输出一遍只会让终端历史变成两份。
     */
    default String dumpHistory() {
        return "";
    }
}
