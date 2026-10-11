package com.agentjava.conversation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * Token 用量锚点（Layer 2 估算用）。
     * baselineTokens = 上次 API 返回的精确 token 数（input + cacheRead + cacheCreation + output）；
     * anchorCount = 锚定时刻的消息数量。压缩后失效，清零。
     */
    private int baselineTokens = 0;
    private int anchorCount = 0;

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

    /** 追加一条已经构造好的消息（压缩重建对话时用）。 */
    public void addRawMessage(Message m) {
        if (m != null) {
            messages.add(m);
        }
    }

    /**
     * 原地替换内部消息列表为另一份对话的内容（深拷贝）。
     * 压缩重建对话时，用「一条摘要 user + 保留的尾部原文」整体替换旧历史。
     */
    public void replaceWith(ConversationManager other) {
        messages.clear();
        for (Message m : other.getMessages()) {
            messages.add(m.copy());
        }
    }

    /** 工具结果丢失时补位用的占位正文；模型看到就知道这步没跑成，而不是"工具返回了空"。 */
    public static final String TOOL_RESULT_LOST_MESSAGE =
            "[error] tool result lost: the session was interrupted before this tool completed";

    /**
     * 修复工具调用链的完整性，返回修复条数。
     *
     * <p>为什么要这层补丁：会话是从磁盘恢复出来的，而磁盘上的记录可能不全——进程在模型
     * 发起 tool_use 之后、工具返回之前被杀掉，恢复出来的历史里就有一次没有结果的调用。
     * 直接把这种历史发给 Anthropic 会被拒绝（tool_use 必须紧跟配对的 tool_result），
     * 而且这类报错是在第一次请求时才炸，很难定位到「其实是上次的会话文件少了一行」。
     *
     * <p>两个方向的修复，配对凭据是 {@code toolUseId}：
     * <ol>
     *   <li><b>孤儿 tool_result</b>：没有对应 tool_use 的结果块，直接丢弃。留着会让协议层
     *       找不到它指的哪次调用，同样是报错。</li>
     *   <li><b>悬空 tool_use</b>：发起了调用却没有结果，补一条内容为错误的 tool_result，
     *       而不是把这次调用删掉。删调用会让模型以为自己从没要过这个文件，
     *       补错误结果则是明确告诉它「这一步没跑成」。</li>
     * </ol>
     *
     * <p><b>刻意不回写磁盘</b>：这是发请求前的运行时修补，不是数据修复。存档保持原样，
     * 每次加载都重新修一遍，避免把一次性的运行时判断固化进持久数据。
     */
    public int fixToolCallChain() {
        Set<String> requested = new LinkedHashSet<>();
        Set<String> answered = new HashSet<>();
        for (Message m : messages) {
            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    requested.add(tu.toolUseId());
                }
            }
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    answered.add(tr.toolUseId());
                }
            }
        }
        int fixed = 0;

        // 1) 孤儿 tool_result：没有任何 tool_use 与之配对 → 丢弃
        Set<String> orphan = new HashSet<>(answered);
        orphan.removeAll(requested);
        if (!orphan.isEmpty()) {
            for (Message m : messages) {
                if (m.getToolResults() == null || m.getToolResults().isEmpty()) {
                    continue;
                }
                List<ToolResultBlock> kept = m.getToolResults().stream()
                        .filter(tr -> !orphan.contains(tr.toolUseId()))
                        .toList();
                if (kept.size() != m.getToolResults().size()) {
                    fixed += m.getToolResults().size() - kept.size();
                    m.setToolResults(kept.isEmpty() ? null : new ArrayList<>(kept));
                }
            }
        }

        // 2) 悬空 tool_use：没有任何 tool_result 与之配对 → 补一条错误结果
        List<String> dangling = requested.stream()
                .filter(id -> !answered.contains(id))
                .toList();
        if (!dangling.isEmpty()) {
            List<ToolResultBlock> blocks = new ArrayList<>(dangling.size());
            for (String id : dangling) {
                blocks.add(new ToolResultBlock(id, TOOL_RESULT_LOST_MESSAGE, true));
            }
            // 补位块要挂在 user 名下。若末尾已经是 user 消息就并入其中，
            // 否则新建——连续两条 user 消息会破坏 API 的严格交替要求
            Message last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (last != null && last.getRole() == Message.Role.USER) {
                List<ToolResultBlock> merged = new ArrayList<>();
                if (last.getToolResults() != null) {
                    merged.addAll(last.getToolResults());
                }
                merged.addAll(blocks);
                last.setToolResults(merged);
            } else {
                addToolResultsMessage(blocks);
            }
            fixed += dangling.size();
        }
        return fixed;
    }

    // ───────────────────────── Token 用量锚点（Layer 2） ─────────────────────────

    /**
     * 锚定本轮真实用量。Agent 在 assistant 消息追加到对话之后调用，传入 API 返回的
     * input/output/cacheRead/cacheCreation。
     */
    public void recordUsageAnchor(int input, int output, int cacheRead, int cacheCreation) {
        int baseline = input + cacheRead + cacheCreation + output;
        if (baseline <= 0) {
            return;
        }
        this.baselineTokens = baseline;
        this.anchorCount = messages.size();
    }

    public boolean hasUsageAnchor() {
        return anchorCount > 0;
    }

    public int getBaselineTokens() {
        return baselineTokens;
    }

    public int getAnchorCount() {
        return anchorCount;
    }

    /** 清零锚点（压缩后旧消息列表已不存在，或手动/紧急压缩前）。 */
    public void clearUsageAnchor() {
        this.baselineTokens = 0;
        this.anchorCount = 0;
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
