package com.agentjava.conversation;

import java.util.ArrayList;
import java.util.List;

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
