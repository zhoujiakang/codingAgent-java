package com.agentjava.subagent;

import com.agentjava.agent.Agent;
import com.agentjava.agent.AgentEvent;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.hook.HookEngine;
import com.agentjava.llm.LlmClient;
import com.agentjava.permission.PermissionMode;
import com.agentjava.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 后台子 Agent 任务管理器。
 *
 * <p>五种状态：PENDING → RUNNING → COMPLETED / FAILED / CANCELLED。任务跑在
 * virtual thread 上（Java 21 的轻量级线程，创建成本极低，正适合「跑完就结束」的活）；
 * 并发保护用 {@code synchronized} 方法，状态字段用 {@code volatile} 让外部轮询可见。
 *
 * <p><b>通知是拉模式</b>：子 Agent 完成时把通知攒进 {@code notifications}，
 * 父 Agent 在每轮收尾时 {@link #drainNotifications} 一次性取走、包成
 * {@code <task-notification>} 注入对话。为什么不用推：往对话里写东西只有 TUI 那一层
 * 握得住，后台线程直接改对话会跟主循环抢同一个非并发列表。
 *
 * <p><b>自动超时</b>：消费事件队列时 60 秒拿不到下一个事件就判定失败返回，
 * 避免上游卡住时这个循环永远等下去、任务永远挂在 RUNNING。
 *
 * <p><b>ESC 手动切换</b>：前台等待的那条线程记在 {@code waitingThread} 上，
 * ESC 触发 {@link #switchToBackground} 把它中断——worker 是另一条虚拟线程，
 * 不受影响，任务继续跑完，结果照样走 task-notification 回传。
 */
public class SubAgentTaskManager {

    public enum TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

    /** 前台等待的结果。 */
    public enum AwaitResult {
        /** 正常跑完。 */
        COMPLETED,
        /** 失败或超时。 */
        FAILED,
        /** 被取消。 */
        CANCELLED,
        /** 被 ESC 切到后台（任务仍在跑）。 */
        SWITCHED,
        /** 前台等待超时，任务转入后台继续跑。 */
        TIMEOUT
    }

    /** 任务快照（对外只读视图）。 */
    public record Task(String id, String name, String agentType, TaskStatus status, String output,
                       String error, boolean backgrounded, long startMillis, Long endMillis) {
    }

    /** 任务完成/失败/取消时攒下的通知，供父 Agent 拉取。 */
    public record TaskNotification(String taskId, String name, String agentType,
                                   TaskStatus status, String output) {
    }

    /** 前台等待的返回：结果类别 + 当时的任务快照。 */
    public record AwaitOutcome(AwaitResult result, Task task) {
    }

    /**
     * 启动一个子 Agent 所需的全部参数。
     *
     * <p>做成一条记录而不是九个形参，是因为两条 spawn 路径（定义式 / Fork）用的是
     * 同一套东西，散成形参列表后每次调用都要对照顺序，容易漏。
     */
    public record SpawnRequest(LlmClient client,
                               ToolRegistry registry,
                               String protocol,
                               String baseSystemPrompt,
                               String workDir,
                               PermissionMode permissionMode,
                               HookEngine hooks,
                               String sessionId,
                               String systemPromptOverride,
                               String prompt,
                               ConversationManager initialConversation,
                               String agentType,
                               int maxTurns,
                               Consumer<AgentEvent> listener) {
    }

    /** 消费事件队列的单次等待上限（秒）：超过就判定子 Agent 卡住。 */
    public static final long EVENT_TIMEOUT_SECONDS = 60L;

    /** 前台等待的轮询间隔（毫秒）。 */
    private static final long POLL_INTERVAL_MILLIS = 200L;

    private final Map<String, TaskEntry> tasks = new LinkedHashMap<>();
    private final List<TaskNotification> notifications = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger();

    private static final class TaskEntry {
        final String id;
        final String name;
        final String agentType;
        final long startMillis;
        volatile TaskStatus status;
        volatile String output;
        volatile String error;
        volatile Thread thread;
        /** 正在前台等待这条任务结果的线程（ESC 切后台时中断它）。 */
        volatile Thread waitingThread;
        /** 已被 ESC 切到后台。 */
        volatile boolean backgrounded;
        volatile Long endMillis;

        TaskEntry(String id, String name, String agentType) {
            this.id = id;
            this.name = name;
            this.agentType = agentType;
            this.startMillis = System.currentTimeMillis();
            this.status = TaskStatus.PENDING;
        }
    }

    // ───────────────────────── 任务登记 ─────────────────────────

    /** 登记一条任务（PENDING），返回任务 ID。调用方拿到 ID 后再 {@link #launch}。 */
    public synchronized String createTask(String name, String agentType) {
        String id = "task_" + nextId.incrementAndGet();
        tasks.put(id, new TaskEntry(id, name, agentType));
        return id;
    }

    /** 登记 + 启动一步到位。 */
    public String spawn(String name, SpawnRequest req) {
        String id = createTask(name, req.agentType());
        launch(id, req);
        return id;
    }

    /** 在虚拟线程上启动任务。 */
    public void launch(String taskId, SpawnRequest req) {
        Thread thread = Thread.startVirtualThread(() -> runWorker(taskId, req));
        synchronized (this) {
            TaskEntry t = tasks.get(taskId);
            if (t != null) {
                t.status = TaskStatus.RUNNING;
                t.thread = thread;
            }
        }
    }

    /**
     * 标记完成。
     *
     * <p>已经被取消的任务不覆盖：取消之后 worker 还会从阻塞的 {@code poll} 上醒来，
     * 走一段收尾逻辑。若让它继续写状态，用户按了取消却看到「失败」，语义就乱了——
     * 取消必须是终态。
     */
    public synchronized void setCompleted(String id, String output) {
        TaskEntry t = tasks.get(id);
        if (t == null || t.status == TaskStatus.CANCELLED) {
            return;
        }
        t.status = TaskStatus.COMPLETED;
        t.output = output;
        t.endMillis = System.currentTimeMillis();
        notifications.add(new TaskNotification(id, t.name, t.agentType, TaskStatus.COMPLETED, output));
    }

    /** 标记失败；同样不覆盖已取消的任务（理由见 {@link #setCompleted}）。 */
    public synchronized void setFailed(String id, String error) {
        TaskEntry t = tasks.get(id);
        if (t == null || t.status == TaskStatus.CANCELLED) {
            return;
        }
        t.status = TaskStatus.FAILED;
        t.error = error;
        t.endMillis = System.currentTimeMillis();
        notifications.add(new TaskNotification(id, t.name, t.agentType, TaskStatus.FAILED, error));
    }

    /** 取消：中断 worker 虚拟线程，让它从 {@code queue.poll} 上退出事件消费循环。 */
    public synchronized boolean cancelTask(String id) {
        TaskEntry t = tasks.get(id);
        if (t == null) {
            return false;
        }
        if (t.status == TaskStatus.COMPLETED || t.status == TaskStatus.FAILED
                || t.status == TaskStatus.CANCELLED) {
            return false;
        }
        t.status = TaskStatus.CANCELLED;
        t.endMillis = System.currentTimeMillis();
        if (t.thread != null) {
            t.thread.interrupt();
        }
        notifications.add(new TaskNotification(id, t.name, t.agentType, TaskStatus.CANCELLED, ""));
        return true;
    }

    /**
     * ESC 手动切换：把指定任务从前台等待切到后台。
     *
     * <p>只中断「等待线程」，不动 worker——子 Agent 继续跑，结果仍会回传。
     */
    public synchronized boolean switchToBackground(String id) {
        TaskEntry t = tasks.get(id);
        if (t == null || t.status != TaskStatus.RUNNING) {
            return false;
        }
        t.backgrounded = true;
        Thread w = t.waitingThread;
        if (w != null) {
            w.interrupt();
        }
        return true;
    }

    /** 找出当前正在前台等待的那条任务并切到后台；没有则返回 null。 */
    public synchronized String switchForegroundToBackground() {
        for (TaskEntry t : tasks.values()) {
            if (t.status == TaskStatus.RUNNING && !t.backgrounded && t.waitingThread != null) {
                t.backgrounded = true;
                Thread w = t.waitingThread;
                if (w != null) {
                    w.interrupt();
                }
                return t.id;
            }
        }
        return null;
    }

    /**
     * 前台等待一条任务跑完。
     *
     * <p>轮询而不是阻塞在 future 上，是因为「等待」这件事要能被 ESC 打断，
     * 还得能区分「被打断去后台了」和「任务失败了」两种结局。
     *
     * <p>被中断时<b>刻意不恢复中断标志</b>：等待线程就是父 Agent Loop 的线程，
     * 恢复标志会让父循环在下一轮迭代直接退出——子任务切后台不该把主 Agent 一起带走。
     */
    public AwaitOutcome awaitCompletion(String id, long maxWaitSeconds) {
        TaskEntry t;
        synchronized (this) {
            t = tasks.get(id);
            if (t == null) {
                return new AwaitOutcome(AwaitResult.FAILED, null);
            }
            t.waitingThread = Thread.currentThread();
        }
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(Math.max(1L, maxWaitSeconds));
        try {
            while (true) {
                synchronized (this) {
                    if (t.status == TaskStatus.COMPLETED) {
                        return new AwaitOutcome(AwaitResult.COMPLETED, snapshot(t));
                    }
                    if (t.status == TaskStatus.FAILED) {
                        return new AwaitOutcome(AwaitResult.FAILED, snapshot(t));
                    }
                    if (t.status == TaskStatus.CANCELLED) {
                        return new AwaitOutcome(AwaitResult.CANCELLED, snapshot(t));
                    }
                    if (System.nanoTime() > deadline) {
                        t.backgrounded = true;
                        return new AwaitOutcome(AwaitResult.TIMEOUT, snapshot(t));
                    }
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    boolean switched;
                    synchronized (this) {
                        switched = t.backgrounded;
                    }
                    return new AwaitOutcome(switched ? AwaitResult.SWITCHED : AwaitResult.CANCELLED,
                            snapshot(t));
                }
            }
        } finally {
            synchronized (this) {
                if (t.waitingThread == Thread.currentThread()) {
                    t.waitingThread = null;
                }
            }
        }
    }

    // ───────────────────────── 查询 ─────────────────────────

    public synchronized Task getTask(String id) {
        TaskEntry t = tasks.get(id);
        return t == null ? null : snapshot(t);
    }

    public synchronized List<Task> listTasks() {
        var out = new ArrayList<Task>(tasks.size());
        for (TaskEntry t : tasks.values()) {
            out.add(snapshot(t));
        }
        return List.copyOf(out);
    }

    public synchronized int runningCount() {
        int n = 0;
        for (TaskEntry t : tasks.values()) {
            if (t.status == TaskStatus.RUNNING || t.status == TaskStatus.PENDING) {
                n++;
            }
        }
        return n;
    }

    /** 取走全部未读通知（拉模式，取完即清空）。 */
    public synchronized List<TaskNotification> drainNotifications() {
        if (notifications.isEmpty()) {
            return List.of();
        }
        var out = List.copyOf(notifications);
        notifications.clear();
        return out;
    }

    private Task snapshot(TaskEntry t) {
        return new Task(t.id, t.name, t.agentType, t.status, t.output, t.error,
                t.backgrounded, t.startMillis, t.endMillis);
    }

    // ───────────────────────── 执行 ─────────────────────────

    /**
     * 子 Agent 的 worker 主体：建 Agent → 起对话 → 消费事件队列到结束。
     *
     * <p>这就是 <b>RunToCompletion</b>：非交互式执行循环。和主 Agent 的
     * 交互式循环只差两点——任务从参数注入而不是等用户输入；模型不再调工具时循环结束，
     * 把最后的文本作为结果返回（而不是等下一轮提问）。
     *
     * <p>Hook 引擎从父 Agent 共享过来，所以子 Agent 里的工具调用照样会触发
     * pre_tool_use / post_tool_use 这些事件。
     */
    private void runWorker(String taskId, SpawnRequest req) {
        try {
            var sub = new Agent(req.client(), req.registry(), req.protocol(), req.baseSystemPrompt());
            sub.setMaxIterations(req.maxTurns() > 0 ? req.maxTurns() : AgentDefinition.DEFAULT_MAX_TURNS);
            if (req.permissionMode() != null) {
                sub.setPermissionMode(req.permissionMode());
            }
            if (req.workDir() != null) {
                sub.setWorkDir(req.workDir());
            }
            if (req.hooks() != null) {
                sub.setHookEngine(req.hooks());
            }
            if (req.sessionId() != null) {
                sub.setSessionId(req.sessionId());
            }

            var conv = req.initialConversation();
            if (conv == null) {
                conv = new ConversationManager();
                if (req.systemPromptOverride() != null && !req.systemPromptOverride().isBlank()) {
                    conv.addSystemReminder(req.systemPromptOverride());
                }
                conv.addUserMessage(req.prompt() == null ? "" : req.prompt());
            }

            var output = new StringBuilder();
            BlockingQueue<AgentEvent> queue = sub.run(conv);
            while (true) {
                AgentEvent event;
                try {
                    event = queue.poll(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    setFailed(taskId, "子 Agent 被中断");
                    return;
                }
                if (event == null) {
                    setFailed(taskId, "等待子 Agent 事件超时（%d 秒）".formatted(EVENT_TIMEOUT_SECONDS));
                    return;
                }
                if (req.listener() != null) {
                    req.listener().accept(event);
                }
                switch (event) {
                    case AgentEvent.StreamText st -> output.append(st.text());
                    case AgentEvent.ErrorEvent err -> {
                        setFailed(taskId, err.message());
                        return;
                    }
                    case AgentEvent.LoopComplete ignored -> {
                        String text = output.toString();
                        setCompleted(taskId, text.isEmpty() ? "（子 Agent 没有产出文本）" : text);
                        return;
                    }
                    default -> {
                        // ToolUseEvent / ToolResultEvent / TurnComplete / UsageEvent …
                        // 由 listener（链路追踪）按需消费，这里只关心终态
                    }
                }
            }
        } catch (RuntimeException e) {
            setFailed(taskId, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
