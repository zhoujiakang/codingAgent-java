package com.agentjava.compact;

import com.agentjava.conversation.ConversationManager;
import com.agentjava.conversation.Message;
import com.agentjava.conversation.ToolResultBlock;
import com.agentjava.conversation.ToolUseBlock;
import com.agentjava.llm.LlmClient;
import com.agentjava.llm.StreamEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * 第二层压缩：上下文压缩（Auto-Compact）。
 *
 * <p>核心公式：估算 token 用量 → 逼近上下文窗口上限时调用 LLM 生成结构化摘要替换旧消息
 * → 保留近期原文 + 恢复块，原地重建对话。设计哲学是「能轻则轻」：先用近乎零成本的第一层
 * （溢写磁盘）把绝大多数膨胀挡在门外；只有当对话真的逼近窗口上限，才动用昂贵但效果强的第二层。
 *
 * <p>估算策略锚定「上一次 API 返回的真实用量」：只对锚点之后新增的消息做字符估算，
 * 误差被压缩在最后一小段增量上，安全余量足以覆盖，避免每轮全量估算累积误差。
 */
public final class ContextCompactor {

    /** 摘要输出预留：压缩后还要生成一份摘要，需从窗口里扣出这块预算。有界：受摘要需求（15~18K tokens）与有效窗口压力共同约束。 */
    private static final int SUMMARY_OUTPUT_RESERVE = 20_000;

    /** 自动触发安全余量（固定值，非百分比）：保护的是单轮波动，与窗口总量无关。 */
    private static final int AUTO_COMPACT_SAFETY_MARGIN = 13_000;

    /** 手动触发安全余量：比自动更小，留给用户明确的压缩动作更接近窗口边缘。 */
    private static final int MANUAL_COMPACT_SAFETY_MARGIN = 3_000;

    /** 尾部保留的 token 预算。 */
    private static final int KEEP_RECENT_TOKENS = 10_000;

    /** 尾部保留的最少消息数（保证不把最近一轮工具调用拆得太碎）。 */
    private static final int MIN_KEEP_MESSAGES = 5;

    /** 尾部保留的 token 上限。 */
    private static final int KEEP_MAX_TOKENS = 40_000;

    /** Token 锚点：上次 API 返回的精确用量 + 锚定时刻的消息数量。 */
    public record UsageAnchor(int baselineTokens, int anchorCount) {
    }

    private ContextCompactor() {
    }

    /**
     * 计算压缩触发线。
     * 有效窗口 = contextWindow − reserve；触发线 = 有效窗口 − 安全边距。
     *
     * <p>200K 窗口下：自动触发线 = 200000 − 20000 − 13000 = 167000；
     * 手动/强制触发线 = 200000 − 20000 − 3000 = 177000。
     */
    public static int computeCompactThreshold(int contextWindow, int maxOutput, boolean manual) {
        int reserve = SUMMARY_OUTPUT_RESERVE;
        if (maxOutput > 0 && maxOutput < reserve) {
            reserve = maxOutput;
        }
        int effectiveWindow = contextWindow - reserve;
        int margin = manual ? MANUAL_COMPACT_SAFETY_MARGIN : AUTO_COMPACT_SAFETY_MARGIN;
        return effectiveWindow - margin;
    }

    // ───────────────────────── Token 估算 ─────────────────────────

    /** 启发式 token 估算：字符数 / 3.5 + 4。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) (text.length() / 3.5 + 4);
    }

    /** 估算一组消息的总 token 数（工具结果只取前 500 字符，避免输入本身膨胀估算）。 */
    public static int estimateTokens(List<Message> messages) {
        int sum = 0;
        for (Message m : messages) {
            sum += estimateTokens(messageToText(m));
        }
        return sum;
    }

    /**
     * 锚定 + 增量估算：有锚点时用精确基准加增量估算，没锚点时全量估算。
     *
     * @param messages 当前完整消息列表
     * @param anchor   锚点；为 null 或越界时退化为全量估算
     */
    public static int currentTokens(List<Message> messages, UsageAnchor anchor) {
        if (anchor == null || anchor.anchorCount() < 0
                || anchor.anchorCount() > messages.size()) {
            return estimateTokens(messages);
        }
        List<Message> appended = messages.subList(anchor.anchorCount(), messages.size());
        return anchor.baselineTokens() + estimateTokens(appended);
    }

    // ───────────────────────── 自动/强制压缩入口 ─────────────────────────

    /**
     * 软硬双阈值 + 熔断的自动压缩入口。每轮调 API 前调用。
     *
     * @return 压缩后的摘要文本；未触发压缩（含被熔断器拦截）时返回空串。
     */
    public static String autoCompact(
            ConversationManager conv,
            LlmClient client,
            int contextWindow,
            String workDir,
            String sessionId,
            RecoveryState recovery,
            List<Map<String, Object>> toolSchemas,
            AutoCompactTrackingState breaker) {

        UsageAnchor anchor = conv.hasUsageAnchor()
                ? new UsageAnchor(conv.getBaselineTokens(), conv.getAnchorCount())
                : null;
        int tokens = currentTokens(conv.getMessages(), anchor);

        int soft = computeCompactThreshold(contextWindow, 0, false);
        if (tokens < soft) {
            return ""; // 软阈值以下：不压缩
        }
        // 硬触发（强制压缩线，在软硬之间）：先于熔断器，最高优先级
        int force = computeCompactThreshold(contextWindow, 0, true);
        if (tokens >= force) {
            return forceCompact(conv, client, contextWindow, workDir, sessionId, recovery, toolSchemas);
        }
        // 介于软硬之间：受熔断器约束
        if (breaker != null && breaker.isTripped()) {
            return "";
        }
        boolean ok;
        String summary;
        try {
            summary = doCompact(conv, client, contextWindow, workDir, sessionId, recovery, toolSchemas);
            ok = true;
        } catch (RuntimeException e) {
            ok = false;
            summary = "";
        }
        if (breaker != null) {
            if (ok) {
                breaker.recordSuccess();
            } else {
                breaker.recordFailure();
            }
        }
        return ok ? summary : "";
    }

    /**
     * 强制压缩：跳过阈值与熔断器，直接生成摘要重建对话。
     * 手动 {@code /compact} 与「prompt_too_long 紧急压缩」都走这里。
     */
    public static String forceCompact(
            ConversationManager conv,
            LlmClient client,
            int contextWindow,
            String workDir,
            String sessionId,
            RecoveryState recovery,
            List<Map<String, Object>> toolSchemas) {
        return doCompact(conv, client, contextWindow, workDir, sessionId, recovery, toolSchemas);
    }

    // ───────────────────────── 压缩核心 ─────────────────────────

    private static String doCompact(
            ConversationManager conv,
            LlmClient client,
            int contextWindow,
            String workDir,
            String sessionId,
            RecoveryState recovery,
            List<Map<String, Object>> toolSchemas) {

        List<Message> messages = conv.getMessages();
        int keepStart = computeKeepStartIndex(messages);
        List<Message> prefix = messages.subList(0, keepStart);
        List<Message> toKeep = new ArrayList<>(messages.subList(keepStart, messages.size()));

        // 只把前缀（messages[0:keepStart]）序列化成文本发给 LLM；工具结果只取前 500 字符。
        // 调用时不传 tools 参数，模型看不到工具定义，自然不会调（且 prompt 明令禁止）。
        String serialized = serializeForSummary(prefix);
        String prompt = SUMMARY_SYSTEM_PROMPT + "\n\n" + serialized;

        ConversationManager summaryConv = new ConversationManager();
        summaryConv.addUserMessage(prompt);

        String raw;
        try {
            raw = generateSummary(client, summaryConv);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Summary interrupted", e);
        }
        String summary = formatCompactSummary(raw);

        // 恢复块：最近访问的文件 + 激活的 Skill + 可用工具（分隔符 {@code ---} 串成一条 user 消息）
        String recoveryText = buildRecoveryAttachment(recovery, toolSchemas);

        StringBuilder content = new StringBuilder();
        content.append("以下是截至目前的对话结构化摘要（旧消息已压缩，便于在有限上下文窗口内继续工作）：\n\n");
        content.append(summary);
        if (!recoveryText.isBlank()) {
            content.append("\n\n---\n\n").append(recoveryText);
        }

        // 重建：一条 user（摘要 + 恢复块）+ 保留的尾部原文（不拆散 tool_use/tool_result 对）
        ConversationManager compacted = new ConversationManager();
        compacted.addUserMessage(content.toString());
        for (Message m : toKeep) {
            compacted.addRawMessage(m);
        }
        conv.replaceWith(compacted);

        // 压缩后旧锚点对应的消息列表已不存在，清零。
        conv.clearUsageAnchor();
        return summary;
    }

    /**
     * 从尾部往回累积，确定「保留哪些近期原文」的起始下标。
     * 满足 KEEP_RECENT_TOKENS 或 MIN_KEEP_MESSAGES 任一条件即停，上限 KEEP_MAX_TOKENS。
     * 配对保护：若切割点落在 tool_result 类型的 user 消息上，往前退一步到 assistant 消息，
     * 保证 tool_use 和 tool_result 不被拆散。
     */
    static int computeKeepStartIndex(List<Message> messages) {
        int n = messages.size();
        if (n == 0) {
            return 0;
        }
        int accumulated = 0;
        int kept = 0;
        int keepStart = n;
        for (int i = n - 1; i >= 0; i--) {
            int msgTokens = estimateTokens(List.of(messages.get(i)));
            if (accumulated + msgTokens > KEEP_MAX_TOKENS && kept > 0) {
                break;
            }
            accumulated += msgTokens;
            kept++;
            keepStart = i;
            if (accumulated >= KEEP_RECENT_TOKENS || kept >= MIN_KEEP_MESSAGES) {
                break;
            }
        }
        while (keepStart > 0 && isToolResultMessage(messages.get(keepStart))) {
            keepStart--;
        }
        return keepStart;
    }

    private static boolean isToolResultMessage(Message m) {
        return m.getToolResults() != null && !m.getToolResults().isEmpty();
    }

    /**
     * 提取最终摘要：找到 {@code <summary>} 标签就取里面的内容；
     * 没有标签就退化为返回整段输出的 strip 结果。
     */
    public static String formatCompactSummary(String raw) {
        if (raw == null) {
            return "";
        }
        int start = raw.indexOf("<summary>");
        int end = raw.indexOf("</summary>");
        if (start >= 0 && end > start) {
            return raw.substring(start + "<summary>".length(), end).strip();
        }
        return raw.strip();
    }

    /** 把前缀消息序列化成纯文本，供 LLM 生成摘要。工具结果只取前 500 字符。 */
    private static String serializeForSummary(List<Message> messages) {
        var sb = new StringBuilder();
        for (Message m : messages) {
            sb.append("[").append(m.getRole().apiValue()).append("]\n");
            sb.append(messageToText(m));
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /** 构造恢复块附件：最近访问的文件 + 激活的 Skill + 可用工具 + 一句提醒。 */
    public static String buildRecoveryAttachment(RecoveryState recovery,
                                                 List<Map<String, Object>> toolSchemas) {
        var sb = new StringBuilder();
        if (recovery != null) {
            List<RecoveryState.FileSnapshot> files = recovery.files();
            if (!files.isEmpty()) {
                sb.append("## 最近访问的文件\n\n");
                int fileBudget = (int) (RecoveryState.MAX_FILE_TOKENS * 3.5);
                for (var f : files) {
                    String c = f.content();
                    if (c.length() > fileBudget) {
                        c = c.substring(0, fileBudget);
                    }
                    sb.append("### ").append(f.path()).append("\n```\n").append(c).append("\n```\n\n");
                }
            }
            List<RecoveryState.SkillDef> skills = recovery.skills();
            if (!skills.isEmpty()) {
                sb.append("## 已激活的 Skill\n\n");
                int budget = (int) (RecoveryState.MAX_SKILL_TOKENS * 3.5);
                int used = 0;
                for (var s : skills) {
                    String entry = "### " + s.name() + "\n" + s.definition() + "\n\n";
                    if (used + entry.length() > budget) {
                        break;
                    }
                    sb.append(entry);
                    used += entry.length();
                }
            }
        }
        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            sb.append("## 可用工具\n\n");
            for (var t : toolSchemas) {
                Object name = t.get("name");
                Object desc = t.get("description");
                sb.append("- ").append(name).append(": ")
                        .append(desc == null ? "" : desc).append("\n");
            }
        }
        sb.append("\n注意：以上是根据摘要恢复的上下文快照，不要凭摘要猜测代码细节；"
                + "如需确切内容，请用 ReadFile 重新读取对应文件。\n");
        return sb.toString();
    }

    // ───────────────────────── 摘要生成 ─────────────────────────

    /** 消费流式响应，累积正文文本，遇到错误抛出，流结束跳出。不传 tools，模型不会调工具。 */
    private static String generateSummary(LlmClient client, ConversationManager summaryConv)
            throws InterruptedException {
        BlockingQueue<StreamEvent> events = client.stream(summaryConv, null);
        var summary = new StringBuilder();
        while (true) {
            StreamEvent ev = events.take();
            if (ev instanceof StreamEvent.TextDelta td) {
                summary.append(td.text());
            } else if (ev instanceof StreamEvent.Error err) {
                throw new RuntimeException("LLM summary failed: " + err.message());
            } else if (ev instanceof StreamEvent.StreamEnd) {
                break;
            }
        }
        return summary.toString();
    }

    /** 把一条消息转成用于估算/序列化的纯文本。 */
    private static String messageToText(Message m) {
        StringBuilder sb = new StringBuilder();
        if (m.getContent() != null) {
            sb.append(m.getContent());
        }
        if (m.getToolUses() != null) {
            for (ToolUseBlock tu : m.getToolUses()) {
                sb.append("\n[tool_use ").append(tu.toolName()).append("] ")
                        .append(tu.arguments() == null ? "" : tu.arguments().toString());
            }
        }
        if (m.getToolResults() != null) {
            for (ToolResultBlock tr : m.getToolResults()) {
                String c = tr.content();
                if (c != null && c.length() > 500) {
                    c = c.substring(0, 500);
                }
                sb.append("\n[tool_result").append(tr.isError() ? " error" : "")
                        .append("] ").append(c == null ? "" : c);
            }
        }
        return sb.toString();
    }

    // ───────────────────────── 熔断器 ─────────────────────────

    /**
     * 自动压缩熔断器：连续 3 次摘要失败则熔断，停止自动触发（forceCompact 不受影响）。
     * 避免「压缩失败 → 再次触发 → 又失败」的死循环，把控制权交还给手动 /compact 或紧急压缩。
     */
    public static final class AutoCompactTrackingState {
        private static final int MAX_CONSECUTIVE_FAILURES = 3;
        private int consecutiveFailures = 0;

        public synchronized void recordSuccess() {
            consecutiveFailures = 0;
        }

        public synchronized void recordFailure() {
            consecutiveFailures = Math.min(MAX_CONSECUTIVE_FAILURES, consecutiveFailures + 1);
        }

        public synchronized boolean isTripped() {
            return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
        }
    }

    // ───────────────────────── 摘要 Prompt ─────────────────────────

    /** 结构化摘要系统提示词：要求先产出 {@code <analysis>} 草稿再产出 {@code <summary>} 正文，正文必须含 9 个部分。 */
    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一个上下文压缩器。下面是一段（可能很长的）对话历史。请把它压缩成一份结构化摘要，\
            在大幅缩短长度的同时，尽量保留所有对后续工作有用的信息。

            请严格按以下两步输出，且只输出这两步内容：

            第一步：在 <analysis> 标签内，先梳理这份对话的关键信息、用户意图、已完成的动作与当前状态。
            第二步：在 <summary> 标签内，给出最终摘要。最终摘要必须包含以下 9 个部分（用标题分隔）：

            1. Primary Request and Intent（用户的请求和意图）
            2. Key Technical Concepts（讨论过的技术概念）
            3. Files and Code Sections（涉及的文件与代码段）
            4. Errors and fixes（遇到的错误和修复方式）
            5. Problem Solving（解决问题的过程）
            6. All user messages（用户说过的所有非工具结果的话，请尽量原文保留，不要改写数字、路径、端口、地址等细节）
            7. Pending Tasks（待办任务）
            8. Current Work（当前工作，这部分要最详细）
            9. Optional Next Step（下一步计划，附上原始对话引用）

            重要约束：
            - 禁止调用任何工具（tool_use），你只能输出文本。
            - "All user messages" 部分请尽量原文逐条列出用户说过的话，这对保留上下文至关重要。
            - 不要编造对话中没有出现过的事实。
            """;
}
