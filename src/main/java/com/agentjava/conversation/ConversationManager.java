package com.agentjava.conversation;

import java.util.ArrayList;
import java.util.List;

import com.agentjava.conversation.ToolResultBlock;
import com.agentjava.conversation.ToolUseBlock;

/**
 * 对话管理器。
 *
 * LLM API 是无状态的，多轮对话全靠客户端维护消息历史：每一轮请求都要把
 * 从第一轮到最新一轮的所有消息打包发过去。这个列表就是那个"历史"。
 *
 * 为什么不直接拿个 ArrayList 到处 append？因为消息的构造有规矩——
 * 工具结果必须挂在 user 名下、assistant 带了工具调用就得紧跟对应结果——
 * 这些规矩散落在各处手写迟早写错。收在这里，对外只暴露语义化操作。
 *
 * 当前只有纯文本对话，所以对外就是 addUserMessage / addAssistantMessage 两个动作。
 */
public class ConversationManager {

    private final List<Message> messages = new ArrayList<>();

    /** 追加一条用户消息。 */
    public Message addUserMessage(String text) {
        Message m = new Message(Message.Role.USER, text, Message.Status.COMPLETE);
        messages.add(m);
        return m;
    }

    /** 追加一条模型回复。 */
    public Message addAssistantMessage(String text) {
        Message m = new Message(Message.Role.ASSISTANT, text, Message.Status.COMPLETE);
        messages.add(m);
        return m;
    }

    /** 追加一条错误占位消息；带 ERROR 状态，不会被 toApiFormat 回传给 API。 */
    public Message addErrorMessage(String text) {
        Message m = new Message(Message.Role.ASSISTANT, text, Message.Status.ERROR);
        messages.add(m);
        return m;
    }

    /**
     * 追加一条带工具调用的助手消息：正文 text 与若干 tool_use 块。
     * 对应的 tool_result 需由 {@link #addToolResultsMessage(List)} 以 user 角色补上。
     */
    public Message addAssistantMessageWithTools(String text, List<ToolUseBlock> toolUses) {
        Message m = new Message(Message.Role.ASSISTANT, text == null ? "" : text, Message.Status.COMPLETE);
        m.setToolUses(toolUses);
        messages.add(m);
        return m;
    }

    /**
     * 追加一条工具结果消息：以 user 角色发送，内部装着一组 tool_result 块。
     * 这样才满足 API 的 user/assistant 交替规则。
     */
    public Message addToolResultsMessage(List<ToolResultBlock> results) {
        Message m = new Message(Message.Role.USER, "", Message.Status.COMPLETE);
        m.setToolResults(results);
        messages.add(m);
        return m;
    }

    /**
     * 注入一条 system-reminder 消息。
     *
     * <p>用 {@code <system-reminder>} XML 标签把动态指令（Plan Mode 提醒、MCP Server 说明、
     * 可用 Skill 列表等）包起来，以 user 角色发送。模型看到标签就知道这是系统补充指令，
     * 而非用户对话，不会去「回复」它。
     *
     * <p>放在 messages 而不是 system 通道，是为了不破坏 system 字段的稳定性，
     * 从而让 Prompt Cache 持续命中。{@link Message#toApiFormat(List)} 会把相邻的
     * user 消息合并，所以注入点与真实用户消息拼成同一条也能正常工作。
     *
     * @param content 提醒正文（不含外层 {@code <system-reminder>} 标签）
     */
    public Message addSystemReminder(String content) {
        String wrapped = "<system-reminder>\n"
                + (content == null ? "" : content.strip())
                + "\n</system-reminder>";
        Message m = new Message(Message.Role.USER, wrapped, Message.Status.COMPLETE);
        messages.add(m);
        return m;
    }

    /** 返回完整历史（内部消息模型，含元数据）。 */
    public List<Message> getMessages() {
        return messages;
    }

    /** 历史条数。 */
    public int size() {
        return messages.size();
    }

    /** 清空历史。 */
    public void clear() {
        messages.clear();
    }

    /**
     * 转成 API 层格式（role + content），带过滤、合并与交替校验。
     *
     * @return 可直接塞进请求体的消息列表
     */
    public List<Message.ApiMessage> toApiMessages() {
        return Message.toApiFormat(messages);
    }
}
