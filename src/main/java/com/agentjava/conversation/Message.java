package com.agentjava.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 内部消息模型。
 *
 * 消息分成两层：
 * <ul>
 *   <li>API 层：只有 role + content，即 {@link ApiMessage}；</li>
 *   <li>内部层：额外带上 ID、状态、时间戳、token 用量这些元数据，即本类。</li>
 * </ul>
 *
 * 内部层存在的意义是：多轮对话要维护历史，就得知道每条消息是什么时候来的、
 * 有没有说完、花了多少 token。这些信息 API 不关心，但界面对账、上下文压缩都靠它。
 */
public final class Message {

    /** 消息角色。API 视角下只有两种：用户发的是 user，模型回的是 assistant。 */
    public enum Role {
        USER("user"),
        ASSISTANT("assistant");

        private final String apiValue;

        Role(String apiValue) {
            this.apiValue = apiValue;
        }

        public String apiValue() {
            return apiValue;
        }
    }

    /** 消息状态。所有消息落地时都是 COMPLETE，字段留给中断与压缩使用。 */
    public enum Status {
        /** 流式还没结束（缓冲区里的半截内容）。 */
        PARTIAL,
        /** 已经完整落地。 */
        COMPLETE,
        /** 本条是错误占位，不回传给 API。 */
        ERROR
    }

    private final String id;
    private final Role role;
    private String content;
    private Status status;
    private final long timestampMillis;
    private int inputTokens;
    private int outputTokens;

    // 工具调用与工具结果（引入 Function Calling 后为非空）
    private List<ToolUseBlock> toolUses;
    private List<ToolResultBlock> toolResults;

    public Message(Role role, String content, Status status) {
        this.id = UUID.randomUUID().toString();
        this.role = role;
        this.content = content == null ? "" : content;
        this.status = status == null ? Status.COMPLETE : status;
        this.timestampMillis = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content == null ? "" : content;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }

    public List<ToolUseBlock> getToolUses() {
        return toolUses;
    }

    public void setToolUses(List<ToolUseBlock> toolUses) {
        this.toolUses = toolUses;
    }

    public List<ToolResultBlock> getToolResults() {
        return toolResults;
    }

    public void setToolResults(List<ToolResultBlock> toolResults) {
        this.toolResults = toolResults;
    }

    /**
     * API 层消息：只有 role 和 content，是要真正发给模型的格式。
     */
    public record ApiMessage(String role, String content) {
    }

    /**
     * 把内部消息列表转换成 API 能接受的干净格式。
     *
     * 做三件事：
     * <ol>
     *   <li>过滤：丢掉空内容和错误占位消息；</li>
     *   <li>合并：把相邻的同角色消息并成一条（Anthropic 虽然会自动合并，
     *       但合并后历史更干净，也避免某些兼容端点不认连续同角色）；</li>
     *   <li>交替校验：合并完仍然 user/assistant 不交替，说明上层拼错了，直接抛错。</li>
     * </ol>
     *
     * @param messages 内部消息列表
     * @return API 层消息列表
     */
    public static List<ApiMessage> toApiFormat(List<Message> messages) {
        List<ApiMessage> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }

        for (Message m : messages) {
            // 1. 过滤：错误占位和空内容都不该占用上下文
            if (m.getStatus() == Status.ERROR || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            // 2. 合并：与上一条同角色就接在一起
            if (!result.isEmpty()
                    && result.get(result.size() - 1).role().equals(m.getRole().apiValue())) {
                ApiMessage prev = result.get(result.size() - 1);
                result.set(result.size() - 1,
                        new ApiMessage(prev.role(), prev.content() + "\n\n" + m.getContent()));
                continue;
            }
            result.add(new ApiMessage(m.getRole().apiValue(), m.getContent()));
        }

        // 3. 交替校验：合并之后必须严格交替，且第一条是 user
        for (int i = 0; i < result.size(); i++) {
            if (i > 0 && result.get(i).role().equals(result.get(i - 1).role())) {
                throw new IllegalStateException(
                        "消息序列未在 user/assistant 之间交替，index=" + i + "，role=" + result.get(i).role());
            }
        }
        if (!result.isEmpty() && !Role.USER.apiValue().equals(result.get(0).role())) {
            throw new IllegalStateException("对话历史必须以 user 消息开头，实际首条为 " + result.get(0).role());
        }
        return result;
    }
}
