package com.agentjava.subagent;

import com.agentjava.agent.AgentEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 父子链路追踪：记录子 Agent 的调用链、Token 消耗与执行状态。
 *
 * <p>为什么需要它：子 Agent 是在后台虚拟线程里跑的，它的中间过程不进主对话。
 * 一旦结果不对，主 Agent 和用户都看不到「它到底读了哪些文件、跑了几轮、花多少 token」，
 * 只能看到一个最终结论。这一层把这些信息按调用链记下来，供 {@code /task info} 回溯。
 *
 * <p>每条记录是一个 Span，通过 {@code parentId} 串成树：
 * <pre>
 *   主 Agent
 *   └─ explore（haiku，30 轮）      ← 定义式，深度 1，终点：拿不到 Agent 工具
 *   └─ fork（继承父上下文）          ← Fork，深度 1
 *      └─ security-reviewer         ← Fork 子 Agent 再派定义式子 Agent，深度 2
 * </pre>
 *
 * <p>链长天然有上限，而且不是靠一个硬编码的深度阈值卡出来的：
 * 定义式子 Agent 在工具列表层就被过滤掉了 Agent 工具（拿不到，自然调不了），
 * Fork 子 Agent 保留 Agent 工具但嵌套 Fork 在调用那一刻被拦截。
 * 所以「Fork 出来的那层还能再派定义式子 Agent，而定义式子 Agent 是这条链的终点」。
 *
 * <p><b>不做跨会话持久化</b>：Trace 只活在内存里，进程退出即消失。
 * 它服务于「刚才那个子 Agent 为什么这么干」，不是审计日志。
 */
public final class TraceRegistry {

    public enum Status {
        RUNNING("运行中"),
        COMPLETED("完成"),
        FAILED("失败"),
        CANCELLED("已取消"),
        BACKGROUND("已转后台");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 一次子 Agent 调用的追踪记录。 */
    public record Span(String id,
                       String parentId,
                       String agentType,
                       String model,
                       String path,
                       int depth,
                       Status status,
                       int inputTokens,
                       int outputTokens,
                       int turns,
                       long startMillis,
                       Long endMillis) {

        /** 跨度（毫秒）；没结束就按当前时间算。 */
        public long elapsedMillis() {
            return (endMillis == null ? System.currentTimeMillis() : endMillis) - startMillis;
        }

        public String modelLabel() {
            return model == null || model.isBlank() ? "inherit" : model;
        }

        public String pathLabel() {
            return "fork".equals(path) ? "Fork" : "定义式";
        }
    }

    private final Map<String, Span> spans = new LinkedHashMap<>();

    /**
     * 开一条追踪记录。
     *
     * @param id        记录 ID；与任务 ID 用同一个值，{@code /task info} 才能对上
     * @param parentId  父记录 ID（主 Agent 直接派发的传 null）
     * @param agentType Agent 类型名
     * @param model     实际使用的模型（null 表示继承父 Agent）
     * @param path      {@code definition} 或 {@code fork}
     */
    public synchronized String start(String id, String parentId, String agentType, String model, String path) {
        int depth = 1;
        if (parentId != null) {
            Span parent = spans.get(parentId);
            if (parent != null) {
                depth = parent.depth() + 1;
            }
        }
        spans.put(id, new Span(id, parentId, agentType, model, path, depth, Status.RUNNING,
                0, 0, 0, System.currentTimeMillis(), null));
        return id;
    }

    /**
     * 把子 Agent 的事件流喂进来：累计 token、记轮数。
     *
     * <p>只挑用量与轮次两类事件——完整事件流太吵，而这两样正好是「这次调用贵不贵、
     * 跑了几步」的答案。
     */
    public synchronized void onEvent(String id, AgentEvent event) {
        Span span = spans.get(id);
        if (span == null) {
            return;
        }
        switch (event) {
            // UsageEvent 里的 input/output 是子 Agent 主循环的累计值，直接覆盖即可
            case AgentEvent.UsageEvent ue ->
                    spans.put(id, new Span(span.id(), span.parentId(), span.agentType(), span.model(),
                            span.path(), span.depth(), span.status(),
                            ue.inputTokens(), ue.outputTokens(), span.turns(),
                            span.startMillis(), span.endMillis()));
            case AgentEvent.TurnComplete tc ->
                    spans.put(id, new Span(span.id(), span.parentId(), span.agentType(), span.model(),
                            span.path(), span.depth(), span.status(),
                            span.inputTokens(), span.outputTokens(), tc.turn(),
                            span.startMillis(), span.endMillis()));
            default -> {
            }
        }
    }

    public synchronized void finish(String id, Status status) {
        Span span = spans.get(id);
        if (span == null) {
            return;
        }
        spans.put(id, new Span(span.id(), span.parentId(), span.agentType(), span.model(),
                span.path(), span.depth(), status, span.inputTokens(), span.outputTokens(),
                span.turns(), span.startMillis(), System.currentTimeMillis()));
    }

    /**
     * 任务状态 → 追踪状态的映射。
     *
     * <p>后台任务完成时没有人同步等着它，最终状态只能从 task-notification 里回填，
     * 这个映射就是回填时用的。
     */
    public static Status fromTaskStatus(SubAgentTaskManager.TaskStatus status) {
        if (status == null) {
            return Status.RUNNING;
        }
        return switch (status) {
            case COMPLETED -> Status.COMPLETED;
            case FAILED -> Status.FAILED;
            case CANCELLED -> Status.CANCELLED;
            case PENDING, RUNNING -> Status.RUNNING;
        };
    }

    public synchronized Span get(String id) {
        return spans.get(id);
    }

    /** 全部记录，按登记顺序。 */
    public synchronized List<Span> spans() {
        return List.copyOf(spans.values());
    }

    /** 定向查询：某条记录及其全部后代（按树序）。 */
    public synchronized List<Span> subtree(String id) {
        var out = new ArrayList<Span>();
        collect(id, out);
        return List.copyOf(out);
    }

    private void collect(String id, List<Span> out) {
        Span span = spans.get(id);
        if (span == null) {
            return;
        }
        out.add(span);
        for (Span candidate : spans.values()) {
            if (id.equals(candidate.parentId())) {
                collect(candidate.id(), out);
            }
        }
    }

    /** 总览一行：调用次数、token 合计。 */
    public synchronized String summary() {
        if (spans.isEmpty()) {
            return "";
        }
        int input = 0;
        int output = 0;
        int running = 0;
        for (Span s : spans.values()) {
            input += s.inputTokens();
            output += s.outputTokens();
            if (s.status() == Status.RUNNING) {
                running++;
            }
        }
        return "子 Agent 调用 %d 次（%d 个仍在运行）· 累计 token ↑%d ↓%d"
                .formatted(spans.size(), running, input, output);
    }

    /** 渲染成缩进树，给 {@code /task info} 用。 */
    public synchronized String render() {
        if (spans.isEmpty()) {
            return "（还没有子 Agent 调用记录）";
        }
        var roots = new ArrayList<Span>();
        for (Span s : spans.values()) {
            if (s.parentId() == null) {
                roots.add(s);
            }
        }
        var sb = new StringBuilder();
        var out = new ArrayList<Span>();
        for (Span root : roots) {
            collect(root.id(), out);
        }
        for (Span s : out) {
            sb.append("  ".repeat(Math.max(1, s.depth())))
                    .append(s.id()).append("  ")
                    .append(s.agentType())
                    .append("（").append(s.pathLabel())
                    .append(" · ").append(s.modelLabel())
                    .append(" · ").append(s.status().label())
                    .append(" · ").append(s.turns()).append(" 轮")
                    .append(" · ").append(formatMillis(s.elapsedMillis()))
                    .append(" · ↑").append(s.inputTokens())
                    .append(" ↓").append(s.outputTokens()).append("）\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String formatMillis(long millis) {
        return millis < 1000 ? millis + "ms" : "%.1fs".formatted(millis / 1000.0);
    }
}
