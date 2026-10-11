package com.agentjava.teams;

import com.agentjava.agent.AgentEvent;
import com.agentjava.permission.PermissionMode;
import com.agentjava.prompt.PlanModePrompt;
import com.agentjava.tui.SpinnerVerbs;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 进程内 teammate 的主循环。
 *
 * <p>生命周期和外部后端一致，只是跑在同一个 JVM 的虚拟线程里：
 * 首轮任务 → plan 审批 → idle 通知 → 轮询邮箱取下一轮 prompt。邮箱轮询间隔 500ms，
 * 用 {@link Thread#isInterrupted()} 作为取消信号。
 */
public final class TeammateRunner {

    public static final String LEAD_NAME = "lead";
    public static final String SHUTDOWN_PREFIX = "[shutdown]";

    /** 空闲时轮询邮箱的间隔（毫秒）。 */
    public static final long IDLE_POLL_MS = 500;

    private TeammateRunner() {}

    /**
     * 在当前线程里跑 teammate 的 Agent 循环，阻塞到收到关闭请求或线程被中断。
     */
    public static void runInProcessTeammate(
            TeamManager.Team team,
            TeamManager.Member member,
            String initialPrompt,
            String addendum
    ) {
        BlockingQueue<AgentEvent> eventOut = new LinkedBlockingQueue<>(32);

        // 建进度追踪器并挂到 member 上
        var progress = new TeammateProgress(member.getName(), team.getName(), SpinnerVerbs.random());
        member.progress = progress;

        if (addendum != null && !addendum.isEmpty()) {
            member.conv.addSystemReminder(addendum);
        }

        // 注入任何待处理的邮箱消息
        injectPendingMessages(team, member.getName(), member.conv);

        // 首轮任务：只有初始 prompt 非空时才作为用户消息注入。
        // 被 tmux/iTerm 拉起的 teammate 进程首轮任务已在邮箱里（上面 injectPendingMessages 已折叠成
        // system reminder），此时初始 prompt 为空，跳过以免注入一条重复的空用户消息。
        if (initialPrompt != null && !initialPrompt.isEmpty()) {
            member.conv.addUserMessage(initialPrompt);
        }

        // 跑首轮
        var agentQueue = member.agent.run(member.conv);
        drainAgentEvents(agentQueue, eventOut, progress);

        // 计划模式的 teammate：一轮跑完意味着它调了 ExitPlanMode，计划已经落到磁盘。
        // 把计划交给 Lead 审批，通过了才解除只读限制开始动手。
        // 放在 idle 通知之前：这时候 teammate 不是闲着等派活，而是卡在审批上，
        // 发一条 idle 会让 Lead 误以为可以塞新任务过来。
        if (!planModeActive(member)) {
            team.sendMessage(member.getName(), LEAD_NAME,
                    createIdleNotification(member.getName(), "available"));
        }

        // 后续轮次：等邮箱消息
        while (!Thread.currentThread().isInterrupted()) {
            if (planModeActive(member)) {
                String next = runPlanApproval(team, member, progress);
                if (next == null) break;
                member.conv.addUserMessage(next);
                agentQueue = member.agent.run(member.conv);
                drainAgentEvents(agentQueue, eventOut, progress);
                continue;
            }

            var result = waitForNextPromptOrShutdown(team, member.getName());
            if (result.shutdown() != null) {
                // 收工前先给 Lead 一个明确答复，让它知道可以回收窗格了。
                // teammate 这里一律同意：它已经处在空闲轮询里，手上没有干到一半的活。
                if (TeamProtocol.SHUTDOWN_REQUEST.equals(result.shutdown().type())) {
                    team.getMailBox().send(LEAD_NAME, TeamProtocol.shutdownResponse(
                            member.getName(), result.shutdown().requestId(), true,
                            "acknowledged, shutting down"));
                }
                break;
            }
            if (result.prompt() == null) break;

            member.conv.addUserMessage(result.prompt());
            agentQueue = member.agent.run(member.conv);
            drainAgentEvents(agentQueue, eventOut, progress);

            if (!planModeActive(member)) {
                team.sendMessage(member.getName(), LEAD_NAME,
                        createIdleNotification(member.getName(), "available"));
            }
        }

        member.active = false;
        progress.setStatus("completed");

        // teammate 退出时持久化对话记录，用于调试
        try {
            Transcript.saveTranscript(team.getName(), member.getName(), member.conv);
        } catch (Exception ignored) {
            // best-effort：持久化失败不影响正常退出
        }
    }

    /**
     * 排空 Lead 在各个团队里的邮箱，返回格式化后的通知字符串。
     * 由 Lead 的 NotificationFn 每轮迭代调用。
     */
    public static List<String> drainLeadMailbox(TeamManager teamMgr) {
        if (teamMgr == null) return List.of();
        var result = new java.util.ArrayList<String>();
        for (String teamName : teamMgr.listTeams()) {
            var team = teamMgr.getTeam(teamName);
            if (team == null) continue;
            var messages = team.getMailBox().readUnread(LEAD_NAME);
            if (messages.isEmpty()) continue;

            var sb = new StringBuilder();
            sb.append("<team-notification team=\"").append(teamName).append("\">\n");
            for (var msg : messages) {
                sb.append("from=").append(msg.from()).append(": ").append(msg.text()).append("\n");
            }
            sb.append("</team-notification>");
            result.add(sb.toString());

            team.getMailBox().markAllRead(LEAD_NAME);
        }
        return result;
    }

    /**
     * 生成 teammate 的 system reminder 附录：告诉它自己的名字、其他队友是谁、
     * 用 SendMessage 通信、纯文本回复对队友不可见。
     */
    public static String buildTeammateAddendum(String teamName, String memberName, List<String> otherMembers) {
        var sb = new StringBuilder();
        sb.append("You are a member of team \"").append(teamName).append("\". ");
        sb.append("Your name is \"").append(memberName).append("\".\n\n");
        if (otherMembers != null && !otherMembers.isEmpty()) {
            sb.append("Other team members: ").append(String.join(", ", otherMembers)).append("\n\n");
        }
        sb.append("You can communicate with teammates using the SendMessage tool.\n");
        sb.append("Messages from teammates arrive as system reminders at the start of each turn.\n");
        sb.append("When you finish your current task, simply stop calling tools — ");
        sb.append("an idle notification will be sent to the lead automatically.");
        return sb.toString();
    }

    /** 把未读的邮箱消息折叠成一条 system reminder 注入对话。 */
    public static void injectPendingMessages(
            TeamManager.Team team, String memberName,
            com.agentjava.conversation.ConversationManager conv
    ) {
        var messages = team.getMailBox().readUnread(memberName);
        if (messages.isEmpty()) return;

        var sb = new StringBuilder("You have new messages:\n\n");
        for (var msg : messages) {
            sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
        }
        conv.addSystemReminder(sb.toString());
        team.getMailBox().markAllRead(memberName);
    }

    public static boolean isShutdownRequest(String message) {
        return message != null && message.strip().startsWith(SHUTDOWN_PREFIX);
    }

    public static String createIdleNotification(String memberName, String reason) {
        return "[idle] %s (reason: %s)".formatted(memberName, reason);
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /** shutdown 非 null 表示收到了关闭请求，字段本身用于取 requestId 做应答。 */
    private record WaitResult(String prompt, FileMailBox.MailMessage shutdown) {}

    private static WaitResult waitForNextPromptOrShutdown(TeamManager.Team team, String memberName) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(IDLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new WaitResult(null, null);
            }

            var messages = team.getMailBox().readUnread(memberName);
            if (messages.isEmpty()) continue;

            for (var msg : messages) {
                if (TeamProtocol.isShutdownRequest(msg)) {
                    team.getMailBox().markAllRead(memberName);
                    return new WaitResult(null, msg);
                }
            }

            // 格式化成下一轮的 prompt 喂给模型
            var sb = new StringBuilder("You have new messages from your team:\n\n");
            for (var msg : messages) {
                sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
            }
            team.getMailBox().markAllRead(memberName);
            return new WaitResult(sb.toString(), null);
        }
        return new WaitResult(null, null);
    }

    /** teammate 是否处在计划模式。只有被 Lead 标了 planModeRequired 的 teammate 才会进这个模式。 */
    private static boolean planModeActive(TeamManager.Member member) {
        return member.agent != null
                && member.agent.getChecker() != null
                && member.agent.getChecker().getMode() == PermissionMode.PLAN;
    }

    /**
     * 把 teammate 写好的计划发给 Lead，阻塞等待批复，返回下一轮该喂给模型的 prompt。
     *
     * <p>teammate 这时候手上是只读权限，等多久都不会造成破坏，所以这里不设超时：
     * 与其超时后自作主张开始改文件，不如一直等着，由用户从 Lead 那边推进。
     * 返回 null 表示被中断，调用方应退出主循环。
     */
    private static String runPlanApproval(TeamManager.Team team, TeamManager.Member member,
                                          TeammateProgress progress) {
        var req = TeamProtocol.planApprovalRequest(member.getName(), readPlanForReview(member));
        team.getMailBox().send(LEAD_NAME, req);
        progress.setStatus("awaiting plan approval");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(IDLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            var messages = team.getMailBox().readUnread(member.getName());
            for (var m : messages) {
                // 只认对应这次请求的批复，别的消息留到下一轮再处理
                if (TeamProtocol.PLAN_APPROVAL_RESPONSE.equals(m.type())
                        && req.requestId().equals(m.requestId())) {
                    team.getMailBox().markAllRead(member.getName());
                    if (TeamProtocol.approved(m)) {
                        // 批准后切回正常权限，teammate 可以改文件了
                        member.agent.getChecker().setMode(PermissionMode.DEFAULT);
                        return "Lead 已批准你的计划，现在按计划开始执行。";
                    }
                    return "Lead 驳回了你的计划，修改意见：" + m.text() + LINE_SEP
                            + "请据此修订计划后再次提交。";
                }
            }
        }
        return null;
    }

    private static final String LINE_SEP = System.lineSeparator();

    /** 读出 teammate 写好的计划全文，交给 Lead 审阅。 */
    private static String readPlanForReview(TeamManager.Member member) {
        try {
            String path = PlanModePrompt.getPlanPath(
                    member.agent != null ? member.agent.getWorkDir() : null);
            String text = java.nio.file.Files.readString(java.nio.file.Path.of(path));
            return text.isBlank() ? "（计划文件为空，teammate 可能未按要求写入计划）" : text;
        } catch (Exception e) {
            return "（计划文件为空，teammate 可能未按要求写入计划）";
        }
    }

    private static void drainAgentEvents(BlockingQueue<AgentEvent> source, BlockingQueue<AgentEvent> sink,
                                         TeammateProgress progress) {
        while (true) {
            AgentEvent event;
            try {
                event = source.poll(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                progress.setStatus("failed");
                return;
            }
            if (event == null) return;
            sink.offer(event);

            // 从 agent 事件里记录进度
            if (event instanceof AgentEvent.ToolUseEvent tue) {
                progress.recordToolUse(tue.toolName(), tue.args());
            } else if (event instanceof AgentEvent.UsageEvent ue) {
                progress.recordTokens(ue.inputTokens(), ue.outputTokens());
            } else if (event instanceof AgentEvent.ErrorEvent) {
                progress.setStatus("failed");
                return;
            } else if (event instanceof AgentEvent.LoopComplete) {
                return;
            }
        }
    }
}
