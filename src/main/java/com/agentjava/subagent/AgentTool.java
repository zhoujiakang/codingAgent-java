package com.agentjava.subagent;

import com.agentjava.agent.AgentEvent;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.conversation.Message;
import com.agentjava.hook.HookEngine;
import com.agentjava.llm.LlmClient;
import com.agentjava.permission.PermissionChecker;
import com.agentjava.permission.PermissionMode;
import com.agentjava.teams.AgentNameRegistry;
import com.agentjava.teams.SpawnDispatcher;
import com.agentjava.teams.TeamManager;
import com.agentjava.teams.TeamTaskTools;
import com.agentjava.teams.TeamTools;
import com.agentjava.teams.TeammateRunner;
import com.agentjava.worktree.AgentWorktree;
import com.agentjava.worktree.WorktreeManager;
import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolCategory;
import com.agentjava.tool.ToolRegistry;
import com.agentjava.tool.ToolResult;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 统一的 Agent 工具：把「起一个子 Agent」包装成一次普通的工具调用。
 *
 * <p>核心洞察是 <b>Agent 与 Tool 的抽象同构</b>——两者都是有名字、有描述、接受参数、
 * 返回结果的可执行单元。所以子 Agent 可以注册进 {@link ToolRegistry}，主 Agent 用它
 * 就跟用 ReadFile 一样自然，完全不需要知道背后是一个完整的 ReAct 循环。
 *
 * <p>为什么是<b>一个</b>工具而不是每种类型注册一个（{@code agent_explore}、
 * {@code agent_plan}…）？因为 Agent 定义是动态加载的，用户在项目目录里新建一个
 * 定义文件，下次调用就能用。如果每个类型注册一个独立工具，工具列表会随定义文件的
 * 增减而变，系统提示也要跟着重新渲染，Prompt Cache 的前缀会被无谓打破。
 * 统一成一个工具、用 {@code subagent_type} 选类型，工具列表始终稳定。
 *
 * <p>两条执行路径：
 * <ul>
 *   <li><b>定义式</b>：指定了 {@code subagent_type}，按定义裁剪工具与模型，
 *       从空白对话开始，默认前台同步执行；</li>
 *   <li><b>Fork</b>：不指定 {@code subagent_type}，继承父 Agent 的完整对话历史
 *       （命中 prompt cache），保留全部工具，<b>总是后台执行</b>。</li>
 * </ul>
 */
public class AgentTool implements Tool {

    /** Fork 子 Agent 的 querySource 标记值：跟着工具实例走，对话压缩影响不到它。 */
    public static final String FORK_QUERY_SOURCE = "agent:builtin:fork";

    /** 链路追踪里的路径标签。 */
    public static final String PATH_DEFINITION = "definition";
    public static final String PATH_FORK = "fork";

    /** 没有指定模型时记在链路追踪里的标签。 */
    public static final String MODEL_INHERIT = "inherit";

    /** 前台等待的上限（秒）：超过就把任务留给后台，不让父 Agent 无限期挂住。 */
    public static final long FOREGROUND_MAX_WAIT_SECONDS = 600L;

    private static final String FORK_BOILERPLATE_TAG = "<fork_boilerplate>";

    /**
     * Fork 子 Agent 的行为约束。
     *
     * <p>为什么需要这么强硬的指令：Fork 子 Agent 继承了父 Agent 的系统提示，
     * 那个提示里写着「你可以创建子 Agent」「你应该和用户确认」这些默认行为。
     * 这段 boilerplate 的作用就是覆盖掉它们，把角色从「交互式助手」强制切换成
     * 「执行型工人」。规则 1 从根源上防止递归 Fork，规则 5 强制输出结构化结果。
     */
    private static final String FORK_BOILERPLATE = FORK_BOILERPLATE_TAG + """

            You are a forked worker process. You are NOT the main agent.
            Rules (non-negotiable):
            1. Do NOT fork again.
            2. Do NOT converse, ask questions, or request confirmation.
            3. Use tools directly: read files, search code, make changes.
            4. Stay strictly within your assigned task scope.
            5. Final report must be under 500 characters, starting with "Scope:".
            </fork_boilerplate>""";

    private final ToolRegistry parentRegistry;

    private LlmClient client;
    private String protocol = "anthropic";
    private String baseSystemPrompt = "";
    private String workDir;
    private String sessionId = "default";
    private PermissionMode permissionMode = PermissionMode.DEFAULT;
    private HookEngine hookEngine;
    private Function<String, LlmClient> modelResolver;
    private Map<String, AgentDefinition> agentDefs = Map.of();
    private SubAgentTaskManager taskManager;
    private TraceRegistry trace;
    private ConversationManager parentConversation;

    /** Worktree 管理器：isolation=worktree 时用于创建/清理隔离副本。 */
    private WorktreeManager worktreeManager;

    /**
     * 团队管理器：传了 team_name 时把子 Agent 作为长期 teammate 派出去。
     * 为 null 时 team_name 参数无效，仍按一次性子 Agent 处理。
     */
    private TeamManager teamManager;

    /** 本工具实例的生成上下文；Fork 子 Agent 里的实例被标成 {@link #FORK_QUERY_SOURCE}。 */
    private volatile String querySource = "";

    /** 本工具实例所处链路的父 span（Fork 子 Agent 里的实例会带上 Fork 那条记录的 ID）。 */
    private volatile String parentSpanId;

    /** agent 类型 Hook 的重入保护：子 Agent 里不能再靠 Hook 起子 Agent。 */
    private boolean hookAgentActive = false;

    public AgentTool(ToolRegistry parentRegistry) {
        this.parentRegistry = parentRegistry;
    }

    // ───────────────────────── 装配 ─────────────────────────

    public void setClient(LlmClient client) {
        this.client = client;
    }

    public void setProtocol(String protocol) {
        if (protocol != null && !protocol.isBlank()) {
            this.protocol = protocol;
        }
    }

    public void setBaseSystemPrompt(String prompt) {
        this.baseSystemPrompt = prompt == null ? "" : prompt;
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    public void setSessionId(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            this.sessionId = sessionId;
        }
    }

    /** 继承给 Fork 子 Agent 的权限模式（定义式子 Agent 用自己定义里的 permissionMode）。 */
    public void setPermissionMode(PermissionMode mode) {
        if (mode != null) {
            this.permissionMode = mode;
        }
    }

    /** Hook 引擎共享：子 Agent 里的工具调用照样触发 pre_tool_use / post_tool_use。 */
    public void setHookEngine(HookEngine hookEngine) {
        this.hookEngine = hookEngine;
    }

    /** 模型别名（haiku / sonnet …）→ 客户端的解析器；没注册时一律复用父客户端。 */
    public void setModelResolver(Function<String, LlmClient> resolver) {
        this.modelResolver = resolver;
    }

    /**
     * 注入已加载的定义表。
     *
     * <p><b>刻意不用 {@code Map.copyOf}</b>：它不保证迭代顺序，而这里要的是稳定的插入
     * 顺序——{@code subagent_type} 的 enum 列表每次渲染都得是同一份，Prompt Cache 的
     * 前缀才不会被无谓打破。
     */
    public void setAgentDefinitions(Map<String, AgentDefinition> defs) {
        this.agentDefs = defs == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(defs));
    }

    public Map<String, AgentDefinition> getAgentDefinitions() {
        return agentDefs;
    }

    public void setTaskManager(SubAgentTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    public void setWorktreeManager(WorktreeManager worktreeManager) {
        this.worktreeManager = worktreeManager;
    }

    /** 注入团队管理器，启用 team_name 参数（长期 teammate 模式）。 */
    public void setTeamManager(TeamManager teamManager) {
        this.teamManager = teamManager;
    }

    public TeamManager getTeamManager() {
        return teamManager;
    }

    public SubAgentTaskManager getTaskManager() {
        return taskManager;
    }

    public void setTrace(TraceRegistry trace) {
        this.trace = trace;
    }

    public TraceRegistry getTrace() {
        return trace;
    }

    public void setParentConversation(ConversationManager parentConversation) {
        this.parentConversation = parentConversation;
    }

    public String getQuerySource() {
        return querySource;
    }

    public void setQuerySource(String querySource) {
        this.querySource = querySource == null ? "" : querySource;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public void setParentSpanId(String parentSpanId) {
        this.parentSpanId = parentSpanId;
    }

    /**
     * 浅复制一份并打上新的 querySource。
     *
     * <p>Fork 用它来标记子 Agent 手里的 AgentTool，使嵌套 Fork 在调用那一刻被拦截。
     * 标记在实例上而不是对话历史里，所以对话被压缩摘要也不会失效。
     */
    public AgentTool cloneWithQuerySource(String qs) {
        AgentTool clone = new AgentTool(this.parentRegistry);
        clone.client = this.client;
        clone.protocol = this.protocol;
        clone.baseSystemPrompt = this.baseSystemPrompt;
        clone.workDir = this.workDir;
        clone.sessionId = this.sessionId;
        clone.permissionMode = this.permissionMode;
        clone.hookEngine = this.hookEngine;
        clone.modelResolver = this.modelResolver;
        clone.agentDefs = this.agentDefs;
        clone.taskManager = this.taskManager;
        clone.trace = this.trace;
        clone.parentConversation = this.parentConversation;
        clone.parentSpanId = this.parentSpanId;
        clone.worktreeManager = this.worktreeManager;
        clone.teamManager = this.teamManager;
        clone.querySource = qs;
        return clone;
    }

    // ───────────────────────── Tool 接口 ─────────────────────────

    @Override
    public String name() {
        return "Agent";
    }

    /**
     * 工具描述里带上当前可用的 Agent 类型清单——这是主 Agent 判断「该派谁」的
     * 唯一信息来源，所以它必须跟着加载结果动态变。
     */
    @Override
    public String description() {
        var sb = new StringBuilder();
        sb.append("派发一个子 Agent 去完成一项独立的子任务。子 Agent 运行在自己的上下文里，")
                .append("看不到当前对话，任务结束后只把结论交回来——用它来避免中间过程污染主上下文。\n\n")
                .append("适合：需要多步探索/搜索的调研、需要独立上下文的实现或审查、")
                .append("可以并行跑的多个不相关子任务。\n\n")
                .append("可用 subagent_type：");
        if (agentDefs.isEmpty()) {
            sb.append("\n- general-purpose：全能力通用（默认）")
                    .append("\n- plan：只读规划，产出分步计划")
                    .append("\n- explore：只读搜索，快速定位代码");
        } else {
            for (String type : AgentLoader.listNames(agentDefs)) {
                sb.append("\n- ").append(type).append("：").append(agentDefs.get(type).whenToUse());
            }
        }
        sb.append("\n\n留空 subagent_type 走 Fork 路径：继承当前对话的完整上下文，")
                .append("且总是后台执行，结果通过 task-notification 回传。\n")
                .append("prompt 要写清楚任务背景——子 Agent 看不到这段对话。");
        return sb.toString();
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public Map<String, Object> schema() {
        List<String> types = agentDefs.isEmpty()
                ? List.of("general-purpose", "plan", "explore")
                : AgentLoader.listNames(agentDefs);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", Map.of(
                "type", "string",
                "description", "一句话描述这次任务（3-5 个词），用于任务列表展示"));
        properties.put("prompt", Map.of(
                "type", "string",
                "description", "交给子 Agent 的任务。要写详细——它看不到当前对话的任何上下文"));
        properties.put("subagent_type", Map.of(
                "type", "string",
                "enum", types,
                "description", "子 Agent 类型。留空则走 Fork 路径（继承当前对话上下文，后台执行）"));
        properties.put("model", Map.of(
                "type", "string",
                "description", "覆盖本次调用使用的模型（如 haiku）；留空则用定义里指定的，再没有就继承父 Agent"));
        properties.put("run_in_background", Map.of(
                "type", "boolean",
                "description", "后台执行，立即返回；结果通过 task-notification 异步回传"));
        properties.put("name", Map.of(
                "type", "string",
                "description", "给这个子 Agent 起个名字，用于任务列表识别"));
        properties.put("isolation", Map.of(
                "type", "string",
                "enum", List.of("worktree"),
                "description", "文件系统隔离模式：worktree 把子 Agent 放进独立的 Git 工作树中运行"));
        properties.put("team_name", Map.of(
                "type", "string",
                "description", "REQUIRED when creating team members. Spawns the agent as a long-running "
                        + "teammate under this team (created via TeamCreate). Unlike regular sub-agents, team "
                        + "members run in their own terminal, persist after the lead returns, and communicate "
                        + "with each other via SendMessage. Without team_name the agent runs as a one-shot "
                        + "sub-agent that blocks and returns inline."));
        properties.put("plan_mode_required", Map.of(
                "type", "boolean",
                "description", "Only meaningful together with team_name. When true, the teammate starts in plan "
                        + "mode: it can read and investigate but cannot modify anything until it submits a plan "
                        + "and you approve it via SendMessage with type='plan_approval_response'. Use it for "
                        + "risky or ambiguous tasks where a wrong direction would cost a lot of rework."));

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("description", "prompt"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", name());
        schema.put("description", description());
        schema.put("input_schema", inputSchema);
        return schema;
    }

    // ───────────────────────── 分发 ─────────────────────────

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        String description = str(safeArgs, "description");
        String prompt = str(safeArgs, "prompt");
        if (description == null || description.isBlank() || prompt == null || prompt.isBlank()) {
            return ToolResult.error("Error: description 和 prompt 都是必填参数");
        }
        String subagentType = str(safeArgs, "subagent_type");
        String modelOverride = str(safeArgs, "model");
        String agentName = str(safeArgs, "name");
        String teamName = str(safeArgs, "team_name");
        boolean planModeRequired = Boolean.TRUE.equals(safeArgs.get("plan_mode_required"));

        // 团队路径：先于 Fork / 子 Agent 判断，team_name 才不会因为走了别的分支而被跳过
        if (teamName != null && !teamName.isBlank() && teamManager != null) {
            AgentDefinition def = (subagentType != null && !subagentType.isBlank())
                    ? resolveSpec(subagentType) : resolveSpec(AgentDefinition.GENERAL_PURPOSE.agentType());
            if (def == null) {
                def = resolveSpec(AgentDefinition.GENERAL_PURPOSE.agentType());
            }
            String memberName = (agentName != null && !agentName.isBlank()) ? agentName : description;
            String isolation = str(safeArgs, "isolation");
            return runAsTeammate(def, teamName, memberName, description, prompt,
                    modelOverride, isolation, planModeRequired);
        }

        String isolation = str(safeArgs, "isolation");
        if ("worktree".equalsIgnoreCase(isolation)) {
            // isolation=worktree：把子 Agent 放进独立的 Git Worktree 里跑
            if (subagentType == null || subagentType.isBlank()) {
                subagentType = AgentDefinition.GENERAL_PURPOSE.agentType();
            }
            AgentDefinition def = resolveSpec(subagentType);
            if (def == null) {
                return ToolResult.error("Error: 未知的 subagent_type '" + subagentType
                        + "'。可用：" + String.join(", ", AgentLoader.listNames(agentDefs)));
            }
            boolean background = Boolean.TRUE.equals(safeArgs.get("run_in_background"));
            return runDefinitionWithWorktree(def, description, prompt, modelOverride, agentName, background);
        }
        // 留空 subagent_type → Fork 路径
        if (subagentType == null || subagentType.isBlank()) {
            return runFork(description, prompt, modelOverride);
        }
        AgentDefinition def = resolveSpec(subagentType);
        if (def == null) {
            return ToolResult.error("Error: 未知的 subagent_type '" + subagentType
                    + "'。可用：" + String.join(", ", AgentLoader.listNames(agentDefs)));
        }
        boolean background = Boolean.TRUE.equals(safeArgs.get("run_in_background"));
        return runDefinition(def, description, prompt, modelOverride, agentName, background);
    }

    /** 类型名匹配：先精确，再按小写兜一层（兼容 Explore / explore 两种写法）。 */
    private AgentDefinition resolveSpec(String type) {
        AgentDefinition def = agentDefs.get(type);
        if (def != null) {
            return def;
        }
        String lower = type.toLowerCase(Locale.ROOT);
        for (var entry : agentDefs.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(lower)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ───────────────────────── 定义式路径 ─────────────────────────

    /**
     * 定义式：按定义裁剪工具与模型，从空白对话开始。
     *
     * <p>工具过滤在这里发生（{@link ToolFilter}），所以定义式子 Agent 的工具集里
     * 根本看不到 Agent 工具——它是这条调用链的终点，这是能力层面的约束，
     * 不是一个硬编码的深度数字。
     */
    private ToolResult runDefinition(AgentDefinition def, String description, String prompt,
                                     String modelOverride, String agentName, boolean background) {
        if (taskManager == null) {
            return ToolResult.error("Error: 没有可用的任务管理器，无法启动子 Agent");
        }
        if (client == null) {
            return ToolResult.error("Error: 还没有可用的 provider，无法启动子 Agent");
        }

        LlmClient subClient = selectClient(def.model(), modelOverride);
        ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, def, background);
        String label = (agentName == null || agentName.isBlank()) ? description : agentName;
        String taskId = taskManager.createTask(truncate(label, 50), def.agentType());
        Consumer<AgentEvent> listener = null;
        if (trace != null) {
            trace.start(taskId, parentSpanId, def.agentType(),
                    modelLabel(def.model(), modelOverride), PATH_DEFINITION);
            final String spanId = taskId;
            listener = ev -> trace.onEvent(spanId, ev);
        }

        var req = new SubAgentTaskManager.SpawnRequest(
                subClient, subRegistry, protocol, baseSystemPrompt, workDir,
                def.permissionMode() == null ? permissionMode : def.permissionMode(),
                hookEngine, sessionId, def.systemPrompt(), prompt, null,
                def.agentType(), def.effectiveMaxTurns(), listener);
        taskManager.launch(taskId, req);

        String head = "子 Agent \"%s\"（%s）".formatted(description, def.agentType());
        if (background) {
            return ToolResult.success(head + "已在后台启动（任务 " + taskId
                    + "）。完成后结果会通过 task-notification 回传，你可以先继续做别的事。");
        }
        return awaitAndReport(taskId, head);
    }

    // ───────────────────────── Worktree 隔离路径 ─────────────────────────

    /**
     * isolation=worktree：把子 Agent 放进独立 Git Worktree 里跑。
     *
     * <p>生命周期：创建 Worktree → 注入上下文通知（告知子 Agent 它在隔离副本中，
     * 父目录传来的路径需翻译）→ 以 Worktree 路径作为子 Agent 的 workDir 启动
     * （工具靠线程局部 cwd 显式取路径，不改进程 cwd）→ 完成后自动清理。
     * 自动清理在链路追踪的监听里触发，所以前台/后台两种路径都能覆盖：无变更就删掉，
     * 有变更就保留并把路径+分支追加到返回结果，让主 Agent 去 review。
     */
    private ToolResult runDefinitionWithWorktree(AgentDefinition def, String description,
                                                 String prompt, String modelOverride,
                                                 String agentName, boolean background) {
        if (worktreeManager == null) {
            return ToolResult.error("Error: Worktree 管理器未初始化，无法使用 isolation=worktree");
        }
        if (client == null) {
            return ToolResult.error("Error: 还没有可用的 provider，无法启动子 Agent");
        }

        // 1) 创建隔离副本（自动生成 ephemeral 名称，可被后台清理回收）
        AgentWorktree.Result wt;
        try {
            wt = AgentWorktree.create(worktreeManager, "HEAD");
        } catch (RuntimeException e) {
            return ToolResult.error("Error: 创建 Worktree 失败：" + e.getMessage());
        }

        // 2) 注入上下文通知，让子 Agent 知道自己在隔离副本里
        String notice = AgentWorktree.buildNotice(workDir, wt.worktreePath());
        String isolatedPrompt = notice + "\n\n" + prompt;

        LlmClient subClient = selectClient(def.model(), modelOverride);
        ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, def, background);
        String label = (agentName == null || agentName.isBlank()) ? description : agentName;
        String taskId = taskManager.createTask(truncate(label, 50), def.agentType());

        // 自动清理：在子 Agent 结束时触发（前台/后台都走这里），结果存进 cleanup[]
        String[] cleanup = {null};
        Consumer<AgentEvent> baseListener = trace == null ? null
                : ev -> trace.onEvent(taskId, ev);
        Consumer<AgentEvent> listener = ev -> {
            if (baseListener != null) {
                baseListener.accept(ev);
            }
            if (ev instanceof AgentEvent.LoopComplete || ev instanceof AgentEvent.ErrorEvent) {
                try {
                    cleanup[0] = AgentWorktree.autoCleanup(
                            wt.worktreePath(), wt.worktreeBranch(), wt.headCommit(), wt.gitRoot());
                } catch (RuntimeException ignored) {
                    // 清理失败不阻断主流程
                }
            }
        };

        var req = new SubAgentTaskManager.SpawnRequest(
                subClient, subRegistry, protocol, baseSystemPrompt, wt.worktreePath(),
                def.permissionMode() == null ? permissionMode : def.permissionMode(),
                hookEngine, sessionId, def.systemPrompt(), isolatedPrompt, null,
                def.agentType(), def.effectiveMaxTurns(), listener);
        taskManager.launch(taskId, req);

        String head = "子 Agent \"%s\"（%s，worktree 隔离）".formatted(description, def.agentType());
        if (background) {
            return ToolResult.success(head + "已在后台启动（任务 " + taskId
                    + "），工作目录为隔离副本 " + wt.worktreePath()
                    + "。完成后结果会通过 task-notification 回传。");
        }
        ToolResult r = awaitAndReport(taskId, head);
        // 前台等待结束时，清理已在监听里跑完，把结果说明追加回去
        if (cleanup[0] != null && !cleanup[0].isBlank()) {
            return new ToolResult(r.output() + cleanup[0], r.isError());
        }
        return r;
    }

    // ───────────────────────── 团队路径 ─────────────────────────

    /**
     * 把子 Agent 作为长期 teammate 派出去。
     *
     * <p>与一次性子 Agent 的区别：teammate 常驻、有独立对话与收件箱，teammate 之间可经
     * SendMessage 点对点通信。spawn 的实际落点（虚拟线程 / tmux / iTerm）由团队后端决定。
     */
    private ToolResult runAsTeammate(AgentDefinition def, String teamName, String agentName,
                                     String description, String prompt, String modelOverride,
                                     String isolation, boolean planModeRequired) {
        if (client == null) {
            return ToolResult.error("Error: 还没有可用的 provider，无法派发 teammate");
        }

        // 团队不存在就顺手建一个：coordinator 模式下 TeamCreate 不在白名单里，
        // 要求 Lead 先建团队再派人，它会卡在第一步。
        TeamManager.Team team = teamManager.getTeam(teamName);
        if (team == null) {
            team = teamManager.createTeam(teamName, TeamManager.detectBackend(),
                    TeammateRunner.LEAD_NAME, description);
        }

        // teammate 名去重：小写、截断到 30 字符，重名加数字后缀
        String memberName = agentName.replaceAll("\\s+", "-").toLowerCase(Locale.ROOT);
        if (memberName.length() > 30) memberName = memberName.substring(0, 30);
        String base = memberName;
        int suffix = 2;
        while (team.hasMember(memberName)) {
            memberName = base + "-" + suffix++;
        }

        ToolRegistry subRegistry = ToolFilter.filterForTeammate(parentRegistry, def);
        // 给 teammate 注入协调工具：按自己的名字发消息，以及读写团队共享任务板
        subRegistry.register(new TeamTools.SendMessageTool(teamManager, memberName));
        subRegistry.register(new TeamTaskTools.TaskCreateTool(teamManager, teamName, memberName));
        subRegistry.register(new TeamTaskTools.TaskGetTool(teamManager, teamName));
        subRegistry.register(new TeamTaskTools.TaskListTool(teamManager, teamName));
        subRegistry.register(new TeamTaskTools.TaskUpdateTool(teamManager, teamName));

        LlmClient subClient = selectClient(def.model(), modelOverride);

        // 收集其他队友名字供附录使用
        List<String> otherMembers = team.memberNames();
        String addendum = TeammateRunner.buildTeammateAddendum(teamName, memberName, otherMembers);

        // 可选的 worktree 隔离
        String workdir = null;
        if ("worktree".equalsIgnoreCase(isolation) && worktreeManager != null) {
            try {
                AgentWorktree.Result wt = AgentWorktree.create(worktreeManager, "HEAD");
                workdir = wt.worktreePath();
                String notice = AgentWorktree.buildNotice(workDir, wt.worktreePath());
                prompt = notice + "\n\n" + prompt;
            } catch (RuntimeException e) {
                return ToolResult.error("Error: 创建 teammate Worktree 失败：" + e.getMessage());
            }
        }

        // 补齐 teammate 元信息，让 config.json 里带上 agent 类型、模型和 worktree 路径
        team.setMemberMeta(memberName, def.agentType(), modelOverride, workdir);

        try {
            var spawnResult = SpawnDispatcher.spawnTeammate(
                    new SpawnDispatcher.SpawnConfig(
                            team, memberName, prompt, addendum,
                            subClient, subRegistry, protocol,
                            providerConfigForSpawn(), workdir,
                            teammateChecker(planModeRequired)));
            return ToolResult.success(
                    "Teammate \"%s\" spawned in team \"%s\" (mode: %s). The teammate is now working on the assigned task."
                            .formatted(memberName, teamName, spawnResult.mode()));
        } catch (Exception e) {
            return ToolResult.error("Error spawning teammate: " + e.getMessage());
        }
    }

    /**
     * 标了 plan_mode_required 的 teammate 以计划模式启动：只能读不能改，
     * 写出计划交 Lead 审批，通过后才切回正常权限。
     */
    private PermissionChecker teammateChecker(boolean planModeRequired) {
        if (!planModeRequired) {
            // 普通 teammate 不带检查器，沿用既有行为
            return null;
        }
        Path root = Path.of(workDir != null ? workDir : System.getProperty("user.dir"));
        return new PermissionChecker(PermissionMode.PLAN, root);
    }

    /** SpawnDispatcher 兜底用的 provider 配置：AgentTool 只持有 prompt 与协议，这里传 null 走默认构造。 */
    private com.agentjava.config.ProviderConfig providerConfigForSpawn() {
        return null;
    }

    // ───────────────────────── Fork 路径 ─────────────────────────

    /**
     * Fork：继承父 Agent 的完整对话历史，总是后台执行。
     *
     * <p><b>为什么强制后台</b>：Fork 子 Agent 的对话前缀很长，首次 API 请求本身就不快，
     * 前台同步等待会把父 Agent 阻塞住；而 Fork 的典型场景是「把多个子任务分发出去」，
     * 统一走后台才能让多个 Fork 并行跑。
     *
     * <p>嵌套检测两道：主检测是跟着工具实例走的 querySource 标记（不在对话历史里，
     * 压缩影响不到）；对话里的 boilerplate 标记扫描是第二道兜底，代价是它本身会随
     * 对话压缩被摘要掉。
     */
    private ToolResult runFork(String description, String prompt, String modelOverride) {
        if (parentConversation == null) {
            return ToolResult.error("Error: Fork 需要父会话上下文");
        }
        if (taskManager == null) {
            return ToolResult.error("Error: Fork 总是后台执行，需要任务管理器");
        }
        if (client == null) {
            return ToolResult.error("Error: 还没有可用的 provider，无法 Fork 子 Agent");
        }
        String nested = "Error: 不能再从 Fork 子 Agent 里 Fork。改用 subagent_type 派发定义式子 Agent。";
        if (FORK_QUERY_SOURCE.equals(querySource)) {
            return ToolResult.error(nested);
        }
        for (Message msg : parentConversation.getMessages()) {
            if (msg.getContent() != null && msg.getContent().contains(FORK_BOILERPLATE_TAG)) {
                return ToolResult.error(nested);
            }
        }

        String task = FORK_BOILERPLATE + "\n\nYour task:\n" + prompt;
        ConversationManager forkedConv = forkConversation(parentConversation, task);

        LlmClient subClient = selectClient(null, modelOverride);
        // 不做工具过滤：Fork 继承父 Agent 的全部工具，保持工具定义字节一致以命中 prompt cache
        ToolRegistry forkedRegistry = ToolFilter.cloneForFork(parentRegistry);
        String taskId = taskManager.createTask("fork: " + truncate(description, 50), "fork");

        if (trace != null) {
            trace.start(taskId, parentSpanId, "fork", modelLabel(null, modelOverride), PATH_FORK);
        }
        // Fork 子 Agent 手里的 AgentTool 已经带上了 fork 标记，再给它记上父 span，
        // 它后续派发的定义式子 Agent 才能挂到这条链下面（深度 2）
        if (forkedRegistry.get(name()) instanceof AgentTool cloned) {
            cloned.setParentSpanId(taskId);
        }
        final String spanId = taskId;
        Consumer<AgentEvent> listener = trace == null ? null : ev -> trace.onEvent(spanId, ev);

        var req = new SubAgentTaskManager.SpawnRequest(
                subClient, forkedRegistry, protocol, baseSystemPrompt, workDir, permissionMode,
                hookEngine, sessionId, null, null, forkedConv, "fork",
                AgentDefinition.DEFAULT_MAX_TURNS, listener);
        taskManager.launch(taskId, req);

        return ToolResult.success("Fork 子 Agent \"%s\" 已在后台启动（任务 %s）。它继承了当前对话的完整上下文，"
                .formatted(description, taskId)
                + "结果会通过 task-notification 回传。");
    }

    /**
     * 复制父对话的全部消息，补上未完成的 tool_use 的占位结果，最后追加任务指令。
     *
     * <p>占位结果这一步不能省：Fork 是在模型发起工具调用的那一刻发生的，
     * 最后一条 assistant 消息带着尚未执行的 tool_use，直接发给 Anthropic 会被拒绝
     * （tool_use 必须紧跟配对的 tool_result）。这里直接复用
     * {@link ConversationManager#fixToolCallChain} 来补，避免再写一份配对逻辑。
     */
    static ConversationManager forkConversation(ConversationManager parent, String task) {
        var forked = new ConversationManager();
        for (Message msg : parent.getMessages()) {
            if (msg.getStatus() == Message.Status.ERROR) {
                continue;
            }
            forked.addRawMessage(msg.copy());
        }
        // 先追加任务、再补链：fixToolCallChain 会把补位结果并进末尾那条 user 消息，
        // 否则它会另起一条 user 消息，跟任务消息连成两条连续的 user（协议要求严格交替）
        forked.addUserMessage(task);
        forked.fixToolCallChain();
        return forked;
    }

    // ───────────────────────── 前台等待 ─────────────────────────

    /** 前台等待一条任务跑完，按结局翻译成给模型看的工具结果。 */
    private ToolResult awaitAndReport(String taskId, String head) {
        var outcome = taskManager.awaitCompletion(taskId, FOREGROUND_MAX_WAIT_SECONDS);
        var task = outcome.task();
        String output = task == null || task.output() == null ? "" : task.output();
        String error = task == null || task.error() == null ? "" : task.error();
        switch (outcome.result()) {
            case COMPLETED -> {
                markTrace(taskId, TraceRegistry.Status.COMPLETED);
                return ToolResult.success(head + "已完成（任务 " + taskId + "）：\n\n" + output);
            }
            case FAILED -> {
                markTrace(taskId, TraceRegistry.Status.FAILED);
                return ToolResult.error(head + "失败：" + error);
            }
            case CANCELLED -> {
                markTrace(taskId, TraceRegistry.Status.CANCELLED);
                return ToolResult.error(head + "已被取消（任务 " + taskId + "）。");
            }
            case SWITCHED -> {
                markTrace(taskId, TraceRegistry.Status.BACKGROUND);
                return ToolResult.success(head + "已转入后台继续运行（任务 " + taskId
                        + "），结果稍后通过 task-notification 回传。");
            }
            case TIMEOUT -> {
                markTrace(taskId, TraceRegistry.Status.BACKGROUND);
                return ToolResult.success(head + "前台等待超过 " + FOREGROUND_MAX_WAIT_SECONDS
                        + " 秒，已转入后台（任务 " + taskId + "），结果稍后通过 task-notification 回传。");
            }
        }
        markTrace(taskId, TraceRegistry.Status.FAILED);
        return ToolResult.error(head + "结局未知（任务 " + taskId + "）");
    }

    private void markTrace(String taskId, TraceRegistry.Status status) {
        if (trace != null) {
            trace.finish(taskId, status);
        }
    }

    // ───────────────────────── 模型选择 ─────────────────────────

    /**
     * 三级优先级：调用参数 {@code model} &gt; 定义里的 {@code model} &gt; 继承父 Agent。
     *
     * <p>{@code inherit}（以及空值）的语义就是「用父 Agent 的客户端」；
     * 别名解析不出来也回退到父客户端，绝不因为一个模型名没认出来就让调用失败。
     */
    private LlmClient selectClient(String defModel, String override) {
        String model = (override != null && !override.isBlank()) ? override : defModel;
        if (model == null || model.isBlank() || MODEL_INHERIT.equalsIgnoreCase(model)) {
            return client;
        }
        if (modelResolver != null) {
            LlmClient resolved = modelResolver.apply(model);
            if (resolved != null) {
                return resolved;
            }
        }
        return client;
    }

    /** 链路追踪里记录的模型标签：没覆盖也没定义时记 inherit。 */
    private String modelLabel(String defModel, String override) {
        String model = (override != null && !override.isBlank()) ? override : defModel;
        return model == null || model.isBlank() ? MODEL_INHERIT : model;
    }

    // ───────────────────────── 给 agent 类型 Hook 用 ─────────────────────────

    /**
     * 供 {@code action.type: agent} 的 Hook 调用：同步跑一个通用子 Agent，返回它的最终文本。
     *
     * <p><b>重入保护</b>：子 Agent 与父 Agent 共享 Hook 引擎，如果 Hook 触发的子 Agent
     * 在执行工具时又撞上同一条 agent Hook，就会无限递归。这里用一个开关挡住第二层——
     * 与「全局禁止列表挡掉 Agent 工具」是同一个思路，只是拦截点不同。
     *
     * <p><b>为什么整个方法不加 synchronized</b>：等待子 Agent 的那段时间里调用方一直
     * 持有锁，而子 Agent 是另一条线程——它若因为同一条 Hook 再调进来就会卡在锁上，
     * 外层又在等它跑完，两边互相等着谁也出不来。所以锁只用在「查开关 + 置位」这两个
     * 瞬间动作上，等待过程不放锁里。
     */
    public String runHookAgent(String prompt) {
        synchronized (this) {
            if (hookAgentActive) {
                return "（嵌套调用已忽略：agent 类型 Hook 不能在子 Agent 里再起子 Agent）";
            }
            hookAgentActive = true;
        }
        if (client == null) {
            synchronized (this) {
                hookAgentActive = false;
            }
            return "（agent 类型 Hook 已配置，但还没有可用的 provider）";
        }
        try {
            AgentDefinition def = resolveSpec(AgentDefinition.GENERAL_PURPOSE.agentType());
            if (def == null) {
                def = AgentDefinition.GENERAL_PURPOSE;
            }
            var result = runDefinition(def, "hook-agent", prompt, null, null, false);
            return result.output();
        } finally {
            synchronized (this) {
                hookAgentActive = false;
            }
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }
}
