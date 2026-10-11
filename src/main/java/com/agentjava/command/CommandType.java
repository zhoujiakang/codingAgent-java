package com.agentjava.command;

/**
 * 命令类型：决定命令跑完之后，调用方怎么处理它的返回值。
 *
 * <p>三种类型对应三条执行路径：
 * <ul>
 *   <li>{@link #LOCAL}：纯本地命令。handler 自己把活干完，返回值是一段要显示给用户
 *       的系统消息。注意「不走 Agent Loop」不等于「不碰 LLM」——/compact 内部也会
 *       调用 LLM 生成摘要，但那次调用是 handler 自己管的，不经过多轮推理；</li>
 *   <li>{@link #LOCAL_UI}：本地命令，但会改变 UI 的行为状态（Plan Mode、清空会话、
 *       切回历史会话）。handler 可以为 null——要操作的资源只有 UI 层才握得住，
 *       这时由 UI 层按命令名兜底；</li>
 *   <li>{@link #PROMPT}：handler 返回的不是展示文本，而是一段要发给 LLM 的 prompt。
 *       命令系统在这里的职责只有一个：把预设好的提示词拼出来。</li>
 * </ul>
 */
public enum CommandType {

    LOCAL,
    LOCAL_UI,
    PROMPT;

    /** {@code /help <命令名>} 详情里展示的类型名。 */
    public String label() {
        return switch (this) {
            case LOCAL -> "local";
            case LOCAL_UI -> "local-ui";
            case PROMPT -> "prompt";
        };
    }
}
