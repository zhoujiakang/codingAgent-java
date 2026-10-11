package com.agentjava.command;

/**
 * 命令执行函数。
 *
 * <p>签名统一意味着框架不需要知道任何命令的具体语义，只管按名字查到 handler 然后调用。
 * 返回值按 {@link Command#type()} 解释：
 * <ul>
 *   <li>{@link CommandType#LOCAL} / {@link CommandType#LOCAL_UI}：要显示给用户的文本；</li>
 *   <li>{@link CommandType#PROMPT}：要发给 LLM 的 prompt。</li>
 * </ul>
 *
 * <p>handler 不碰 UI 控件：要显示什么走返回值，要改 UI 行为走
 * {@link CommandContext#ui()}。这条约束是命令层能独立于具体 TUI 的前提。
 */
@FunctionalInterface
public interface CommandHandler {

    String handle(CommandContext ctx);
}
