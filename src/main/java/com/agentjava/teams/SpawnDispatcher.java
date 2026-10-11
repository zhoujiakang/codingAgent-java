package com.agentjava.teams;

import com.agentjava.config.ProviderConfig;
import com.agentjava.llm.LlmClient;
import com.agentjava.permission.PermissionChecker;
import com.agentjava.tool.ToolRegistry;

import java.util.List;

/**
 * teammate spawn 的统一入口，按团队后端分发到三种执行方式。
 *
 * <p>三种后端的差异只在「跑在哪里」：in-process 用虚拟线程跑在同一 JVM；
 * tmux / iTerm 拉起一个完整的 CLI 进程。但通信全部走 {@link FileMailBox}，
 * 所以上层的 SendMessage / 任务板逻辑对三种后端完全一致。
 */
public final class SpawnDispatcher {

    public record SpawnConfig(
            TeamManager.Team team,
            String memberName,
            String task,
            String addendum,
            LlmClient client,
            ToolRegistry registry,
            String protocol,
            ProviderConfig providerConfig,
            String workdir,
            /**
             * teammate 的权限检查器。Lead 派人时标了 plan_mode_required，
             * 这里就是一个 PLAN 模式的 checker，teammate 只能读不能改，直到计划获批。
             */
            PermissionChecker checker
    ) {}

    public record SpawnResult(
            TeamManager.TeamMode mode,
            String paneId
    ) {}

    private SpawnDispatcher() {}

    public static SpawnResult spawnTeammate(SpawnConfig config) throws Exception {
        var team = config.team();
        var mode = team.getMode();

        // 把 teammate 名字登记到全局名称注册表，供 SendMessage 按名字解析投递
        AgentNameRegistry.getInstance().register(config.memberName(), config.memberName());

        switch (mode) {
            case IN_PROCESS -> {
                var member = team.addMember(config.memberName(), config.client(),
                        config.registry(), config.protocol(), config.providerConfig());
                member.agent.setChecker(config.checker());
                if (config.workdir() != null) {
                    member.agent.setWorkDir(config.workdir());
                }
                member.active = true;
                member.thread = Thread.startVirtualThread(() ->
                        TeammateRunner.runInProcessTeammate(team, member, config.task(), config.addendum()));
                return new SpawnResult(mode, null);
            }
            case TMUX -> {
                // 任务写入邮箱，新进程首次轮询即可获取
                if (config.task() != null && !config.task().isEmpty()) {
                    team.sendMessage(TeammateRunner.LEAD_NAME, config.memberName(), config.task());
                }
                String cliCommand = buildTeammateCLI(team.getName(), config.memberName(), config.workdir());
                String paneId = TmuxBackend.spawnTmuxTeammate(team.getName(), config.memberName(), cliCommand);
                recordExternalMember(team, config.memberName(), paneId);
                return new SpawnResult(mode, paneId);
            }
            case ITERM -> {
                // iTerm2 后端：在新标签页中启动队友进程
                if (config.task() != null && !config.task().isEmpty()) {
                    team.sendMessage(TeammateRunner.LEAD_NAME, config.memberName(), config.task());
                }
                String itermCmd = buildTeammateCLI(team.getName(), config.memberName(), config.workdir());
                String tabId = ITermBackend.spawnITermTeammate(team.getName(), config.memberName(), itermCmd);
                recordExternalMember(team, config.memberName(), tabId);
                return new SpawnResult(mode, tabId);
            }
            default -> throw new IllegalStateException("Unsupported team mode: " + mode);
        }
    }

    /**
     * 构建 teammate 进程的 shell 命令。
     * 格式：{@code cd '<workdir>' && '<agentjava>' --teammate --team-name <t> --agent-name <n>}
     */
    public static String buildTeammateCLI(String teamName, String memberName, String workdir) {
        String wd = workdir != null ? workdir : System.getProperty("user.dir");
        // 取当前进程的可执行文件路径，队友进程用它自己再拉一个
        String bin = ProcessHandle.current().info().command().orElse("agentjava");
        return "cd %s && %s --teammate --team-name %s --agent-name %s".formatted(
                shellQuote(wd), shellQuote(bin), shellQuote(teamName), shellQuote(memberName));
    }

    /** 对含特殊字符的路径加单引号并转义内部单引号。 */
    static String shellQuote(String s) {
        if (s.matches("[a-zA-Z0-9_./-]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 外部进程模式下，本进程只知道 teammate 的名字，先放一个占位成员好让 SendMessage 找得到。 */
    private static void recordExternalMember(TeamManager.Team team, String name, String paneId) {
        var member = new TeamManager.Member(name, null, null);
        member.active = true;
        synchronized (team) {
            team.members.put(name, member);
        }
    }
}
