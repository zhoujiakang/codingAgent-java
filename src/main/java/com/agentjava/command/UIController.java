package com.agentjava.command;

import java.util.Locale;

/**
 * UI 控制接口：UI 层暴露给命令的操作面。
 *
 * <p>命令不知道自己跑在什么界面上——它只知道「我能显示一条系统消息」「我能把一段话
 * 发出去」「我能切换 Plan Mode」。TUI 具体实现可以在 {@code AgentModel} 里，
 * 将来换成 Web 或者 headless 也一样能用同一套命令。
 *
 * <p>{@code refreshStatus()} 在本项目里是空实现（Bubble Tea 每帧重绘视图，不需要手动刷），
 * 但接口保留：换到一个不是每帧重绘的 UI 上，这条钩子就是必须的。
 */
public interface UIController {

    /** 显示一条系统消息（不是来自模型的回复）。 */
    void addSystemMessage(String text);

    /** 把一段文本当作用户输入交给 Agent（会走完整的 Agent Loop，消耗 token）。 */
    void sendUserMessage(String text);

    /** 切换 Plan Mode：UI 的整体行为模式随之变化（工具可用性、状态栏、输入框占位文案）。 */
    void setPlanMode(boolean enabled);

    /** 当前已用 token 数。 */
    int getTokenCount();

    /** 命令改变了 UI 状态之后调用一次。 */
    void refreshStatus();
}
