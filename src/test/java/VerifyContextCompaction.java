package com.agentjava.compact;

import com.agentjava.conversation.ConversationManager;
import com.agentjava.conversation.Message;
import com.agentjava.conversation.ToolResultBlock;
import com.agentjava.conversation.ToolResultBudget;
import com.agentjava.llm.LlmClient;
import com.agentjava.llm.StreamEvent;
import com.agentjava.config.ProviderConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 离线验证：不依赖真实 LLM / 网络，覆盖两层压缩的核心逻辑。
 *
 * 通过 {@link FakeSummaryClient} 模拟「能吐出结构化摘要」的模型，
 * 把 forceCompact / autoCompact 的完整链路也跑一遍。
 */
public class VerifyContextCompaction {

    // ───────────────────────── 第一层：ToolResultBudget ─────────────────────────

    /** 单条超限 → 写盘换预览；预览含固定标签与路径，且可回读。 */
    @Test
    public void testSingleSpill() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "ch8-spill-single");
        // 用 persistLargeResult 走完整路径
        String preview = ToolResultBudget.persistLargeResult(dir, "toolu_single", "y".repeat(60_000));
        assertTrue(preview.contains("<persisted-output>"), "预览必须含 <persisted-output> 标签");
        assertTrue(preview.contains("Full output saved to:"), "预览必须含落盘路径");
        // 写盘后文件存在且内容完整
        Path file = dir.resolve("toolu_single.txt");
        assertTrue(java.nio.file.Files.exists(file), "溢写文件应已落盘");
        String onDisk = java.nio.file.Files.readString(file);
        assertEquals(60_000, onDisk.length(), "落盘内容应完整");
        System.out.println("[OK] testSingleSpill: 单条 60K 结果已落盘并替换预览");
    }

    /** 聚合超限：单条都没超，但合计超 200K → 从最大的开始落盘。 */
    @Test
    public void testAggregateSpill() {
        List<ToolResultBlock> results = new ArrayList<>();
        // 3 条各 90K，合计 270K > 200K，但单条 < 50K（不触发单条溢写）
        results.add(new ToolResultBlock("t1", "a".repeat(90_000), false));
        results.add(new ToolResultBlock("t2", "b".repeat(90_000), false));
        results.add(new ToolResultBlock("t3", "c".repeat(90_000), false));
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "ch8-spill-agg");
        var out = ToolResultBudget.apply(results, dir, java.util.Set.of());
        int spilled = 0;
        for (var b : out) {
            if (b.content().contains("<persisted-output>")) {
                spilled++;
            }
        }
        assertTrue(spilled >= 1, "聚合超限额时应至少落盘一条");
        // 落盘后总量应回到限额内
        long total = 0;
        for (var b : out) total += b.content().length();
        assertTrue(total <= ToolResultBudget.MESSAGE_AGGREGATE_LIMIT,
                "聚合落盘后总量应 <= " + ToolResultBudget.MESSAGE_AGGREGATE_LIMIT + "，实际 " + total);
        System.out.println("[OK] testAggregateSpill: 270K 聚合结果已压回 200K 内（落盘 " + spilled + " 条）");
    }

    /** 回读豁免：ReadFile 读回溢写目录下的文件时，不应再被溢写。 */
    @Test
    public void testSpillReadbackExemption() {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "ch8-spill-rb");
        String bigFile = dir.resolve("toolu_secret.txt").toString();
        // 先存一份大内容
        ToolResultBudget.persistLargeResult(dir, "toolu_secret", "z".repeat(60_000));
        // 模拟模型用 ReadFile 读回这个文件
        assertTrue(ToolResultBudget.isSpillReadback("ReadFile",
                Map.of("file_path", bigFile), dir), "读回溢写目录文件应判为回读豁免");
        assertFalse(ToolResultBudget.isSpillReadback("ReadFile",
                Map.of("file_path", "/etc/passwd"), dir), "读外部文件不应豁免");
        assertFalse(ToolResultBudget.isSpillReadback("Bash",
                Map.of("file_path", bigFile), dir), "非 ReadFile 不应豁免");

        // 落盘后豁免集生效：apply 保留原文而非再溢写
        List<ToolResultBlock> results = new ArrayList<>();
        results.add(new ToolResultBlock("toolu_secret", "z".repeat(60_000), false));
        var out = ToolResultBudget.apply(results, dir, java.util.Set.of("toolu_secret"));
        assertFalse(out.get(0).content().contains("<persisted-output>"),
                "豁免项不应被再次溢写");
        System.out.println("[OK] testSpillReadbackExemption: ReadFile 回读免溢写");
    }

    // ───────────────────────── 第二层：ContextCompactor 纯函数 ─────────────────────────

    @Test
    public void testThresholdsAndEstimation() {
        // 200K 窗口：自动线 = 200000-20000-13000 = 167000；强制线 = 200000-20000-3000 = 177000
        assertEquals(167_000, ContextCompactor.computeCompactThreshold(200_000, 0, false));
        assertEquals(177_000, ContextCompactor.computeCompactThreshold(200_000, 0, true));
        // maxOutput 小于 reserve 时，reserve 被压缩
        assertEquals(200_000 - 8_000 - 13_000, ContextCompactor.computeCompactThreshold(200_000, 8_000, false));

        // 估算：3500 字符 ≈ 1004 tokens（length/3.5 + 4）
        int est = ContextCompactor.estimateTokens("x".repeat(3_500));
        assertEquals((int) (3_500 / 3.5 + 4), est);

        // 锚定增量：baseline=100，锚点后新增约 1004 tokens
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("hello");
        conv.addAssistantMessage("hi");
        conv.recordUsageAnchor(50, 50, 0, 0); // baseline = 50+0+0+50 = 100, anchorCount=2
        conv.addUserMessage("x".repeat(3_500)); // 第 3 条，新增约 1004 tokens
        int tokens = ContextCompactor.currentTokens(conv.getMessages(),
                new ContextCompactor.UsageAnchor(conv.getBaselineTokens(), conv.getAnchorCount()));
        assertEquals(100 + (int) (3_500 / 3.5 + 4), tokens, "锚定增量估算应等于 baseline + 新增");
        System.out.println("[OK] testThresholdsAndEstimation: 阈值/估算/锚定增量均正确");
    }

    @Test
    public void testKeepStartIndexPairing() {
        // 构造 tail: assistant(tool_use) -> user(tool_result) -> assistant -> user
        List<Message> msgs = new ArrayList<>();
        msgs.add(new Message(Message.Role.USER, "u1 " + "a".repeat(200), Message.Status.COMPLETE));
        msgs.add(new Message(Message.Role.ASSISTANT, "a1", Message.Status.COMPLETE));
        // 末尾塞一个 tool_use / tool_result 对
        {
            Message m = new Message(Message.Role.ASSISTANT, "did work", Message.Status.COMPLETE);
            m.setToolUses(List.of(new com.agentjava.conversation.ToolUseBlock("tu1", "ReadFile", Map.of())));
            msgs.add(m);
        }
        {
            Message m = new Message(Message.Role.USER, "", Message.Status.COMPLETE);
            m.setToolResults(List.of(new ToolResultBlock("tu1", "result " + "r".repeat(200), false)));
            msgs.add(m);
        }
        msgs.add(new Message(Message.Role.ASSISTANT, "final", Message.Status.COMPLETE));
        int keepStart = ContextCompactor.computeKeepStartIndex(msgs);
        // 不能把 tool_result 单独留在 prefix：若切在 tool_result 上，应回退到它的 assistant
        Message cut = msgs.get(keepStart);
        assertFalse(cut.getToolResults() != null && !cut.getToolResults().isEmpty(),
                "切割点不应落在 tool_result 消息上（否则拆散 tool_use/tool_result 对）");
        System.out.println("[OK] testKeepStartIndexPairing: 切割点未拆散工具调用对 (keepStart=" + keepStart + ")");
    }

    @Test
    public void testFormatSummary() {
        String raw = "thinking...\n<analysis>some draft</analysis>\n<summary>本对话在做一个 Java Agent</summary>\ntrailing";
        assertEquals("本对话在做一个 Java Agent", ContextCompactor.formatCompactSummary(raw));
        // 无标签退化
        assertEquals("plain text".strip(), ContextCompactor.formatCompactSummary("plain text").strip());
        System.out.println("[OK] testFormatSummary: <summary> 提取正确");
    }

    @Test
    public void testRecoveryAttachment() {
        RecoveryState rs = new RecoveryState();
        rs.recordFile("/a.txt", "content A ".repeat(50));
        rs.recordFile("/b.txt", "content B ".repeat(50));
        rs.recordSkill("pdf", "PDF 技能定义");
        String att = ContextCompactor.buildRecoveryAttachment(rs, List.of(
                Map.of("name", "ReadFile", "description", "读取文件")));
        assertTrue(att.contains("/a.txt"), "恢复块应含最近访问文件");
        assertTrue(att.contains("PDF 技能定义"), "恢复块应含激活的 Skill");
        assertTrue(att.contains("ReadFile"), "恢复块应含可用工具");
        assertTrue(att.contains("不要凭摘要猜测代码细节"), "恢复块应含提醒");
        System.out.println("[OK] testRecoveryAttachment: 文件/技能/工具/提醒均生成");
    }

    // ───────────────────────── 端到端：forceCompact（用假客户端） ─────────────────────────

    @Test
    public void testForceCompactEndToEnd() throws Exception {
        ConversationManager conv = new ConversationManager();
        // 造一长段历史，确保 keepStart > 0（尾部保留，前缀被摘要）
        for (int i = 0; i < 12; i++) {
            conv.addUserMessage("用户陈述 #" + i + "：" + "背景信息 ".repeat(80));
            conv.addAssistantMessage("助手回复 #" + i + "：" + "分析内容 ".repeat(80));
        }
        int beforeSize = conv.size();
        RecoveryState rs = new RecoveryState();
        rs.recordFile("/src/Main.java", "public class Main {}".repeat(20));

        FakeSummaryClient client = new FakeSummaryClient();
        String summary = ContextCompactor.forceCompact(
                conv, client, 200_000, "/tmp", "sess-end2end", rs,
                List.of(Map.of("name", "ReadFile", "description", "读取文件")));

        assertFalse(summary.isBlank(), "应返回非空摘要");
        assertTrue(conv.size() < beforeSize / 2,
                "压缩后历史应显著缩短（before=" + beforeSize + ", after=" + conv.size() + "）");
        String first = conv.getMessages().get(0).getContent();
        assertTrue(first.contains("结构化摘要"), "压缩后首条应为摘要");
        assertTrue(first.contains("/src/Main.java"), "摘要后应附带恢复块（最近访问文件）");
        assertFalse(conv.hasUsageAnchor(), "压缩后用量锚点应清零");
        System.out.println("[OK] testForceCompactEndToEnd: 12 轮历史(" + beforeSize
                + ") → 摘要重建(" + conv.size() + ")，首条含恢复块，锚点清零");
    }

    @Test
    public void testAutoCompactTriggersOnSmallWindow() throws Exception {
        // 上下文窗口设得很小，软/硬阈值都成负数 → 任意对话都触发强制压缩
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("你好，帮我看下项目结构");
        conv.addAssistantMessage("好的，我来梳理".repeat(30));
        FakeSummaryClient client = new FakeSummaryClient();
        RecoveryState rs = new RecoveryState();
        String s = ContextCompactor.autoCompact(conv, client, 5_000, "/tmp", "sess-auto", rs,
                List.of(), new ContextCompactor.AutoCompactTrackingState());
        assertFalse(s.isBlank(), "小窗口下 autoCompact 应触发压缩");
        System.out.println("[OK] testAutoCompactTriggersOnSmallWindow: 小窗口自动触发压缩");
    }

    @Test
    public void testAutoCompactNoTriggerOnLargeWindow() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("hi");
        FakeSummaryClient client = new FakeSummaryClient();
        String s = ContextCompactor.autoCompact(conv, client, 200_000, "/tmp", "sess-noop",
                new RecoveryState(), List.of(), new ContextCompactor.AutoCompactTrackingState());
        assertEquals("", s, "大窗口 + 极小对话不应触发压缩");
        System.out.println("[OK] testAutoCompactNoTriggerOnLargeWindow: 大窗口下不误触发");
    }

    // ───────────────────────── 假客户端：吐出结构化摘要 ─────────────────────────

    /** 忽略输入，直接吐出一段带 <analysis>/<summary> 的流式响应。 */
    static final class FakeSummaryClient implements LlmClient {
        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv) {
            return stream(conv, null);
        }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
            BlockingQueue<StreamEvent> q = new LinkedBlockingQueue<>();
            Thread.startVirtualThread(() -> {
                try {
                    String text = "<analysis>draft</analysis>\n"
                            + "<summary>本对话在实现一个 Java 版 Coding Agent；用户要求梳理项目结构，"
                            + "已读取若干文件；下一步继续分析模块依赖。</summary>";
                    // 按小块吐出，模拟真实流式
                    for (String part : text.split("(?<=。)|(?<=>)")) {
                        q.put(new StreamEvent.TextDelta(part));
                    }
                    q.put(new StreamEvent.StreamEnd("end_turn", 100, 50, 0, 0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            return q;
        }

        @Override
        public ProviderConfig config() {
            return null;
        }

        @Override
        public void setSystemPrompt(String prompt) {
        }
    }
}
