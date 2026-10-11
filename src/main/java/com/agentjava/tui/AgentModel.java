package com.agentjava.tui;

import com.agentjava.agent.Agent;
import com.agentjava.agent.AgentEvent;
import com.agentjava.agent.StreamingExecutor;
import com.agentjava.command.CommandContext;
import com.agentjava.command.CommandParser;
import com.agentjava.command.CommandRegistry;
import com.agentjava.command.CommandType;
import com.agentjava.command.UIController;
import com.agentjava.compact.ContextCompactor;
import com.agentjava.compact.RecoveryState;
import com.agentjava.config.ProviderConfig;
import com.agentjava.hook.EventName;
import com.agentjava.hook.Hook;
import com.agentjava.hook.HookConfig;
import com.agentjava.hook.HookContext;
import com.agentjava.hook.HookEngine;
import com.agentjava.hook.HookResult;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.history.HistoryStore;
import com.agentjava.llm.LlmClient;
import com.agentjava.mcp.McpManager;
import com.agentjava.mcp.McpServerConfig;
import com.agentjava.memory.MemoryConsolidator;
import com.agentjava.memory.MemoryManager;
import com.agentjava.permission.PermissionMode;
import com.agentjava.permission.PermissionResponse;
import com.agentjava.prompt.PlanModePrompt;
import com.agentjava.prompt.PromptBuilder;
import com.agentjava.session.SessionManager;
import com.agentjava.session.SessionMessage;
import com.agentjava.skill.SkillCatalog;
import com.agentjava.subagent.AgentLoader;
import com.agentjava.subagent.AgentTool;
import com.agentjava.subagent.SubAgentTaskManager;
import com.agentjava.subagent.TraceRegistry;
import com.agentjava.worktree.WorktreeManager;
import com.agentjava.worktree.WorktreeSession;
import com.agentjava.worktree.WorktreeSessionStore;
import com.agentjava.skill.SkillExecutor;
import com.agentjava.skill.SkillForkHost;
import com.agentjava.tool.ToolRegistry;
import com.agentjava.tool.impl.LoadSkillTool;
import com.agentjava.tui.tea.Ansi;
import com.agentjava.tui.tea.Command;
import com.agentjava.tui.tea.KeyPressMessage;
import com.agentjava.tui.tea.Message;
import com.agentjava.tui.tea.Model;
import com.agentjava.tui.tea.Program;
import com.agentjava.tui.tea.UpdateResult;
import com.agentjava.tui.tea.WindowSizeMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 主 TUI 模型。
 *
 * 真正复杂的"调 LLM → 执行工具 → 喂回结果"主循环搬到了 {@link Agent} 里。
 * 这个模型只负责三件事：把用户输入交给 Agent、把 Agent 吐出的 {@link AgentEvent}
 * 渲染成界面、处理本地输入命令。Agent 跑在虚拟线程上，事件通过队列回投到主线程，
 * 所以这里的 update() 始终是纯粹的状态变换，不碰网络、不碰 IO。
 *
 * 一轮对话的节奏：用户输入 → 进历史 → Agent 循环多轮直到完成 → 事件流实时渲染。
 *
 * <p>除主循环外，它还承担两个身份：
 * <ul>
 *   <li><b>UI 层</b>：实现 {@link UIController}，把 REPL 的能力打包交给命令系统，
 *       这样 {@code com.agentjava.command} 里的命令不需要认识任何 TUI 内部类；</li>
 *   <li><b>命令主机</b>：持有一个 {@link CommandRegistry}，负责「判断输入是不是命令 →
 *       查命令 → 按类型路由 → 把结果渲染成副作用指令」这条链路。</li>
 * </ul>
 *
 * <p>它同时还多一个身份：<b>Skill 宿主</b>。它持有 {@link SkillCatalog}、把 Skill
 * 注册成斜杠命令、实现 {@link SkillForkHost} 给 fork 模式提供隔离子 Agent 运行时。
 * 之所以落在这一层而不是 {@link Agent} 里，是因为「起一个子 Agent」需要 LLM 客户端
 * 与 provider，这两样只在 TUI 启动时才确定。
 *
 * <p>它再添一个身份：<b>Hook 宿主</b>。会话级（session_start/end）、轮次级
 * （turn_start/end）、{@code error}、{@code command_execute} 这几个事件的插入点都在这一层；
 * 消息级与工具级的在 {@link Agent} 与 {@link StreamingExecutor} 里。prompt 类型 Hook 的输出
 * 也要由它在每轮收尾时取走并注入对话——往对话里写东西只有它握得住。
 *
 * <p>{@code com.agentjava.tui.tea.Command}（TEA 的副作用指令）与
 * {@code com.agentjava.command.Command}（Slash Command 定义）同名，后面一律用全限定名区分。
 */
public class AgentModel implements Model, UIController {

    public static final String APP_NAME = "AgentJava";
    public static final String VERSION = "0.1.0";

    /**
     * 退出命令。真正接客的是 {@link CommandRegistry#createDefault()} 里
     * 那条 hidden 的 {@code exit} 命令，这里保留常量只是给启动提示文案用。
     */
    public static final String EXIT_COMMAND = "/exit";

    /**
     * 手动压缩的 token 下限。低于这个值的上下文不值得跑一次摘要调用——
     * 压缩本身要用掉一轮 LLM，省下来的 token 还不够付这次请求。
     */
    private static final int MIN_COMPACT_TOKENS = 5_000;

    /** Slash Command 注册中心：所有内置命令在这里一次性登记完。 */
    private final CommandRegistry commands = CommandRegistry.createDefault();

    private final List<ProviderConfig> providers;
    private final String workingDir;

    /** Worktree 管理器（repoRoot = workingDir）。 */
    private WorktreeManager worktreeManager;

    /**
     * 当前已进入的 Worktree 路径；为 null 表示还在主目录。
     * 进入后主 Agent 与子 Agent 的 workDir 都退回它，工具据此显式取 cwd。
     */
    private String activeWorktree = null;
    private String systemPrompt;
    private final ConversationManager conversation = new ConversationManager();

    /** 工具注册中心：六个内置工具 + 共享的文件状态缓存 + 启动期接入的 MCP 工具。 */
    private final ToolRegistry registry = ToolRegistry.createDefault();

    /** MCP 连接管理器；未配置 MCP Server 时为 null。 */
    private McpManager mcpManager;

    /** 启动期 MCP 接入状态，打印在 banner 之后给用户一个明确反馈。 */
    private String mcpStatus = "";

    private Program program;
    private AppState state;
    private int selectedIndex = 0;
    private ProviderConfig activeProvider;
    private LlmClient llmClient;

    /** Plan Mode 开关：开启后 Agent 只暴露只读工具，并注入"只规划不改动"的指引。 */
    private boolean planMode = false;

    /** 权限模式：决定只读/写/命令各自的默认处置（见 PermissionMode 矩阵）。 */
    private PermissionMode permissionMode = PermissionMode.DEFAULT;

    /** 是否正在等待用户对一个权限请求的拍板（确认期间拦截普通输入）。 */
    private boolean awaitingPermission = false;

    /** 当前待决的权限请求 future；用户按键后由 UI 回传 PermissionResponse。 */
    private CompletableFuture<PermissionResponse> pendingPermission;

    /**
     * 会话 ID：格式 {@code YYYYMMDD-HHMMSS-xxxx}。
     * 既是会话存档的文件名，也是工具结果溢写目录的隔离键——两者一致，
     * 恢复历史会话后才能接着读当时的溢写文件。
     */
    private String sessionId = SessionManager.newId();

    /** 上下文窗口（token）：从 provider 配置读取，驱动压缩阈值。 */
    private int contextWindow = 200_000;

    /** 跨轮次累积的恢复状态（最近访问的文件 / 激活的 Skill），随会话持久。 */
    private final RecoveryState recovery = new RecoveryState();

    // ─────────────────── Skill 技能包系统 ───────────────────

    /**
     * Skill 目录：启动时按「内置 → 用户级 → 项目级」三级扫描，只加载 frontmatter。
     * 正文等 {@code LoadSkill} 或 {@code /<name>} 触发时再读（两阶段加载）。
     */
    private SkillCatalog skillCatalog;

    /** Skill 宿主实现：登记激活、给 fork 模式起子 Agent。 */
    private final SkillHostImpl skillHost = new SkillHostImpl();

    /** 按需加载工具：模型判断意图匹配后调用它取完整 SOP。 */
    private final LoadSkillTool loadSkillTool = new LoadSkillTool();

    /**
     * 本会话激活过的 Skill 名（按激活顺序，去重）。
     * Skill 可能由后台的 Agent 线程经 LoadSkill 激活，所以用并发容器。
     */
    private final List<String> activeSkills = new CopyOnWriteArrayList<>();

    /**
     * 已经通知过模型的 Skill 名。首条 system-reminder 会带上全量清单，
     * 之后只补增量——重复注入同一段清单只会白烧 token。
     */
    private final Set<String> announcedSkills = new LinkedHashSet<>();

    // ─────────────────── Hook 生命周期钩子 ───────────────────

    /**
     * Hook 引擎：启动时从三处配置追加合并加载，之后在生命周期节点上被触发。
     * 会话级 / 轮次级的插入点在 TUI 这一层，消息级与工具级的由 {@link Agent} 与
     * {@link StreamingExecutor} 各自持有同一个实例去触发。
     */
    private HookEngine hookEngine;

    /** 配置里解析不出、或校验没过的条目，攒着给 {@code /hooks} 展示。 */
    private final List<String> hookErrors = new ArrayList<>();

    /** 启动期的一行状态：加载了几条 Hook、有没有配置问题。 */
    private String hookStatus = "";

    /**
     * Hook 运行期的错误日志。
     *
     * <p>Hook 跑在 Agent 的虚拟线程上，直接往终端写会跟全屏界面打架，往
     * {@link #pendingSystemMessages} 里塞又要担心并发。攒进同步列表、由 {@code /hooks}
     * 展示最稳妥——反正是辅助机制，出错只要能查到就够。
     */
    private final List<String> hookLog = Collections.synchronizedList(new ArrayList<>());

    // ─────────────────── SubAgent 子任务分发 ───────────────────

    /** 后台子 Agent 任务管理器：登记、等待、取消、通知回传。 */
    private SubAgentTaskManager taskManager;

    /** 父子链路追踪：调用链 / Token 消耗 / 执行状态。只活在内存里，不做跨会话持久化。 */
    private TraceRegistry traceRegistry;

    /**
     * 统一的 Agent 工具。注册进 {@link #registry} 之后，模型派子 Agent 跟调 ReadFile
     * 没有区别；它需要的 LLM 客户端、协议、System Prompt 在 provider 激活时补齐。
     */
    private AgentTool agentTool;

    /** Agent 定义文件解析失败清单，启动期提示、{@code /tasks} 可查。 */
    private final List<String> agentErrors = new ArrayList<>();

    /** 启动期的一行状态：有几个子 Agent 可用。 */
    private String agentStatus = "";

    /** 自动压缩熔断器，跨轮次累积。 */
    private final ContextCompactor.AutoCompactTrackingState breaker =
            new ContextCompactor.AutoCompactTrackingState();

    /** 本轮是否因错误收场，用于在 LoopComplete 时避免重复落地。 */
    private boolean agentErrored = false;

    /** 跨会话记忆：项目指令发现 + 自动记忆双目录。 */
    private final MemoryManager memory;

    /** 记忆治理器：门控通过后 fork 子 Agent 合并/清理旧记忆。 */
    private final MemoryConsolidator consolidator;

    /** 输入历史（存在 ~/.agentjava/prompt_history.jsonl，跟人走不跟项目走）。 */
    private final HistoryStore history = new HistoryStore();

    /**
     * 已落盘到会话存档的消息条数。
     * 每次追加只写 {@code [persistedCount, size)} 这一段增量，避免重复写整份历史。
     */
    private int persistedCount = 0;

    /** 输入缓冲：Alt+Enter / Ctrl+J 插入换行，Enter 提交。 */
    private final StringBuilder input = new StringBuilder();

    /**
     * 命令通过 {@link UIController#addSystemMessage} 攒下来的输出行。
     * Model 本身不写终端，攒起来交给 {@link Command.Println} 一次性打出去。
     */
    private final List<String> pendingSystemMessages = new ArrayList<>();

    /**
     * 命令通过 {@link UIController#sendUserMessage} 投递的待发文本。
     * 命令跑完之后如果有值，就把它当作一轮新的用户输入交给 Agent。
     */
    private String pendingUserMessage;

    /** 流式缓冲：片段实时进这里，本轮结束才落成一条历史消息（由 Agent 负责落地）。 */
    private final StringBuilder streamBuf = new StringBuilder();
    private boolean streaming = false;
    private long requestStartMillis = 0;
    private String spinnerVerb = SpinnerVerbs.random();

    private int lastInputTokens = 0;
    private int lastOutputTokens = 0;
    private boolean usageAvailable = false;

    private int width = 80;
    private int height = 24;
    private boolean bannerPrinted = false;

    public AgentModel(List<ProviderConfig> providers, String workingDir) {
        this(providers, workingDir, List.of());
    }

    public AgentModel(List<ProviderConfig> providers, String workingDir, List<McpServerConfig> mcpConfigs) {
        this.providers = List.copyOf(providers);
        this.workingDir = workingDir == null ? System.getProperty("user.dir") : workingDir;
        // 跨会话记忆：构造即确保双目录存在（用户级 ~/.agentjava/memory 与项目级 .agentjava/memory）
        this.memory = new MemoryManager(this.workingDir);
        this.consolidator = MemoryConsolidator.forMemoryManager(this.memory, this.workingDir);
        // 先用环境探测（此时 provider 未激活，model 未知）构建一次七模块 System Prompt；
        // 激活 provider 拿到 model 后会用真实 model 重建，让 Environment 段落带上模型名。
        this.systemPrompt = PromptBuilder.buildSystemPrompt(
                PromptBuilder.detectEnvironment(null), null);
        // 启动期接入 MCP：连接配置的 Server、发现工具、注册进 ToolRegistry。
        // 单个 Server 失败只收集错误，不阻断其余 Server，也不阻止启动。
        if (mcpConfigs != null && !mcpConfigs.isEmpty()) {
            try {
                this.mcpManager = new McpManager(mcpConfigs);
                var result = mcpManager.registerAllTools(registry);
                if (!result.errors().isEmpty()) {
                    mcpStatus = Styles.ERROR.render("⚠ MCP 接入异常: " + String.join("; ", result.errors()));
                } else if (result.serverCount() > 0) {
                    mcpStatus = Styles.ACCENT.render("✻ 已连接 " + result.serverCount() + " 个 MCP Server，注册 "
                            + result.tools().size() + " 个工具");
                }
                // 退出时统一关闭 MCP 连接，释放子进程与传输资源
                final McpManager mgr = mcpManager;
                Runtime.getRuntime().addShutdownHook(new Thread(mgr::shutdown));
            } catch (Exception e) {
                mcpStatus = Styles.ERROR.render("⚠ MCP 初始化失败: " + e.getMessage());
            }
        }
        // 子 Agent 装配要早于 provider 激活：activateProvider 里要把客户端回灌给 AgentTool
        bootstrapSubAgents();
        // 只有一个 provider 时直接进入对话，多个时才让用户选（F2）
        if (this.providers.size() == 1) {
            this.state = AppState.CHAT;
            activateProvider(0);
        } else {
            this.state = AppState.PROVIDER_SELECT;
        }
        // Skill 装配要在记忆装配之前：Skill 清单和记忆索引进的是同一条 system-reminder，
        // 而记忆注入只在「对话为空」时动作，顺序反了会把清单挤到助手消息之后
        bootstrapSkills();
        // Hook 装配放在记忆之前：session_start 的 prompt 类 Hook 要注入对话，
        // 而记忆装配在最后会把 persistedCount 对齐到「注入完成」的位置
        bootstrapHooks();
        // 记忆装配放在最后：它要往对话里注入内容，得等 provider 选择分支确定之后再做，
        // 保证无论走哪条路径都注入一次
        bootstrapMemory();
        // 系统级与会话级的开场事件：startup 先于 session_start（启动 → 开会话）
        fireHook(HookContext.of(EventName.STARTUP));
        fireHook(HookContext.of(EventName.SESSION_START));
        flushHookNotifications();
        persistedCount = conversation.size();
    }

    /**
     * 新会话启动时的记忆装配。
     *
     * <p>顺序有讲究：自动记忆索引必须先注入，因为 {@link MemoryManager#injectMemories}
     * 只在对话为空时才动作（它要补一条助手确认来凑成合法的 user/assistant 交替）。
     * 项目指令与 Skill 清单走 system-reminder 追加在后，不占这个「空」的位置。
     *
     * <p>注入的内容刻意<b>不进会话存档</b>：它们每次启动都会重新装配，
     * 存进 JSONL 只会让历史越滚越脏。{@code persistedCount} 直接跳过这一段。
     */
    private void bootstrapMemory() {
        memory.injectMemories(conversation);
        String instructions = memory.loadInstructions();
        if (!instructions.isBlank()) {
            conversation.addSystemReminder(instructions);
        }
        injectSkillSection();
        persistedCount = conversation.size();
    }

    // ───────────────────────── Skill 装配 ─────────────────────────

    /**
     * 启动时装配 Skill 系统，四步：
     * <ol>
     *   <li>三级扫描目录，只加载 frontmatter（阶段一）；</li>
     *   <li>把每个 Skill 注册成 {@code /<name>} 斜杠命令；</li>
     *   <li>注册 {@code LoadSkill} 工具，让模型能按意图按需加载（阶段二）；</li>
     *   <li>注册 {@code /skill} 管理命令。</li>
     * </ol>
     */
    private void bootstrapSkills() {
        skillCatalog = SkillCatalog.loadCatalog(workingDir);
        for (String name : skillCatalog.names()) {
            registerSkillCommand(name);
        }

        loadSkillTool.setCatalog(skillCatalog);
        loadSkillTool.setHost(skillHost);
        loadSkillTool.setForkHost(skillHost);
        registry.register(loadSkillTool);

        commands.register(new com.agentjava.command.Command(
                "skill", "Skill 管理：list / info / reload", new String[]{"skills"},
                CommandType.LOCAL, "/skill [list|info <名称>|reload]", "", this::handleSkill));
    }

    /**
     * 把 Skill 清单（只有 name + description）以 system-reminder 注入对话。
     *
     * <p>放在 messages 而不是 System Prompt，是为了不让 System Prompt 跟着项目变——
     * 它一变，Prompt Cache 的整段前缀就失效了。
     */
    private void injectSkillSection() {
        if (skillCatalog == null) {
            return;
        }
        String section = skillCatalog.buildSection(workingDir);
        if (section.isBlank()) {
            return;
        }
        conversation.addSystemReminder(section);
        announcedSkills.addAll(skillCatalog.names());
    }

    /**
     * 把一个 Skill 注册成斜杠命令。
     *
     * <p><b>内置命令优先</b>：名字撞上已有命令就直接跳过。Skill 是用户自己写的
     * Markdown，命名冲突随时可能发生；让内置命令赢，才能保证 {@code /help}、
     * {@code /clear} 这些保底能力永远不会被一个第三方 Skill 顶掉。
     *
     * <p>命令类型固定为 {@link CommandType#PROMPT}：返回值不是展示文本，而是要交给
     * Agent 的提示词，SOP 借此进入对话触发下一轮循环。
     */
    private void registerSkillCommand(String name) {
        if (commands.find(name).isPresent()) {
            return;
        }
        var skill = skillCatalog.get(name);
        if (skill.isEmpty()) {
            return;
        }
        String captured = name;
        String description = skill.get().meta().description();
        commands.register(new com.agentjava.command.Command(
                captured, description + " [skill]", new String[]{},
                CommandType.PROMPT, "/" + captured + " [参数]", "",
                ctx -> runSkill(captured, ctx.args())));
    }

    /**
     * 执行一个 Skill：按 SKILL.md 声明的 mode 分流。
     *
     * <p>两条触发路径（用户敲 {@code /<name>}、模型调 LoadSkill）都走这里，
     * 保证「Skill 声明的隔离意图」在两条路上一致生效。
     *
     * <p>fork 模式需要 LLM 客户端；provider 还没激活时降级成 inline——
     * 保留功能比坚持隔离更重要，何况此时也才刚启动，没有上下文需要隔离。
     */
    private String runSkill(String name, String args) {
        var found = skillCatalog.getFull(name);
        if (found.isEmpty()) {
            return "[skill error] not found: " + name;
        }
        var skill = found.get();
        String arguments = args == null ? "" : args;
        if (skill.meta().isFork() && llmClient != null) {
            try {
                String result = SkillExecutor.executeFork(skill, arguments, skillHost);
                addSystemMessage("✻ Skill /" + name + " 已在隔离上下文中执行完毕，结论如下");
                return result;
            } catch (RuntimeException e) {
                return "✗ Skill /" + name + " fork 执行失败：" + e.getMessage();
            }
        }
        return SkillExecutor.executeInline(skill, arguments, skillHost);
    }

    /**
     * 每轮提交前检查 Skill 目录有没有增删。
     *
     * <p>目录 modtime 变了才 reload：新增/删除会改变目录项，而已有 Skill 的正文改动
     * 由 {@link SkillCatalog#getFull} 的按需重读兜住，不必整体重建。
     * 新出现的 Skill 以 system-reminder 增量补进对话，不重建 System Prompt——
     * 同样是保住 Prompt Cache 的前缀。
     */
    private void refreshSkillsIfNeeded() {
        if (skillCatalog == null || !skillCatalog.needsReload()) {
            return;
        }
        skillCatalog.reload(workingDir);
        for (String name : skillCatalog.names()) {
            registerSkillCommand(name);
        }
        List<String> fresh = new ArrayList<>();
        for (String name : skillCatalog.names()) {
            if (announcedSkills.add(name)) {
                fresh.add(name);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("新检测到以下 Skill，可用 /<name> 或 LoadSkill 调用：\n");
        for (String name : fresh) {
            String desc = skillCatalog.getFull(name).map(s -> s.meta().description()).orElse("");
            sb.append("- /").append(name).append("：").append(desc).append('\n');
        }
        conversation.addSystemReminder(sb.toString());
    }

    // ───────────────────────── /skill 管理命令 ─────────────────────────

    /** {@code /skill [list|info <名称>|reload]}。 */
    private String handleSkill(com.agentjava.command.CommandContext ctx) {
        if (skillCatalog == null) {
            return "（Skill 系统未初始化）";
        }
        String[] parts = splitOnce(ctx.args());
        return switch (parts[0]) {
            case "", "list" -> skillList();
            case "info" -> skillInfo(parts[1]);
            case "reload" -> skillReload();
            default -> "未知子命令：" + parts[0] + "\n用法：/skill [list|info <名称>|reload]";
        };
    }

    private String skillList() {
        var metas = skillCatalog.list();
        if (metas.isEmpty()) {
            return "（还没有安装任何 Skill）\n\n把 Skill 放到下面任一目录即可自动加载：\n"
                    + "  项目级：" + Path.of(workingDir, ".agentjava", "skills") + "\n"
                    + "  用户级：" + Path.of(System.getProperty("user.home"), ".agentjava", "skills");
        }
        StringBuilder sb = new StringBuilder("已加载 Skill（").append(metas.size()).append(" 个）\n\n");
        for (var meta : metas) {
            String desc = meta.description() == null ? "" : meta.description();
            if (desc.length() > 56) {
                desc = desc.substring(0, 56) + "…";
            }
            sb.append("  ").append(pad("/" + meta.name(), 22))
                    .append(pad(SkillCatalog.sourceLabel(skillCatalog.source(meta.name())), 8))
                    .append(desc).append('\n');
        }
        if (!activeSkills.isEmpty()) {
            sb.append("\n本会话已激活：").append(String.join("、", activeSkills)).append('\n');
        }
        sb.append("\n/skill info <名称> 看详情 · /skill reload 热重载");
        return sb.toString();
    }

    private String skillInfo(String rawName) {
        String name = rawName == null ? "" : rawName.strip();
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.isEmpty()) {
            return "用法：/skill info <Skill 名称>";
        }
        if (skillCatalog.get(name).isEmpty()) {
            return "✗ 找不到 Skill: " + name + "（/skill list 看全部）";
        }
        return skillCatalog.describe(name);
    }

    private String skillReload() {
        skillCatalog.reload(workingDir);
        for (String name : skillCatalog.names()) {
            registerSkillCommand(name);
        }
        announcedSkills.clear();
        announcedSkills.addAll(skillCatalog.names());
        return Styles.ACCENT.render("✻ 已重新加载 Skill 目录，共 " + skillCatalog.size() + " 个")
                + Styles.MUTED.render("（改动后的 SKILL.md 正文在下次调用时生效）");
    }

    private static String pad(String text, int width) {
        if (text == null) {
            text = "";
        }
        int w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            w += cp > 0x2E80 ? 2 : 1;
            i += Character.charCount(cp);
        }
        return text + " ".repeat(Math.max(1, width - w));
    }

    // ───────────────────────── SubAgent 装配 ─────────────────────────

    /**
     * 启动时装配子 Agent 系统，四步：
     * <ol>
     *   <li>四级扫描 Agent 定义（插件 → 内置 → 用户 → 项目），同名后者覆盖前者；</li>
     *   <li>建任务管理器与链路追踪；</li>
     *   <li>注册统一的 Agent 工具，让模型可以像调普通工具一样派发子 Agent；</li>
     *   <li>注册 {@code /tasks} 与 {@code /task} 两条管理命令。</li>
     * </ol>
     *
     * <p>客户端相关的东西（LLM 客户端、协议、System Prompt、模型解析器）留给
     * {@link #activateProvider} 补齐——这一章的子 Agent 是在对话中按需创建的，
     * 启动时 provider 可能还没选。
     */
    private void bootstrapSubAgents() {
        taskManager = new SubAgentTaskManager();
        traceRegistry = new TraceRegistry();

        var loaded = AgentLoader.loadAll(Path.of(workingDir));
        agentErrors.addAll(loaded.errors());

        agentTool = new AgentTool(registry);
        agentTool.setAgentDefinitions(loaded.definitions());
        agentTool.setTaskManager(taskManager);
        agentTool.setTrace(traceRegistry);
        agentTool.setParentConversation(conversation);
        agentTool.setWorkDir(workingDir);
        registry.register(agentTool);

        // Worktree：管理器以 workingDir 为 repoRoot，过期清理阈值 24 小时
        worktreeManager = new WorktreeManager(workingDir, List.of(), 24);
        agentTool.setWorktreeManager(worktreeManager);
        // 启动时尝试从会话文件恢复已进入的 Worktree（--resume 支持）
        tryRestoreWorktreeSession();

        // /worktree 命令：list / create / enter / exit / remove 五个子命令
        commands.register(new com.agentjava.command.Command(
                "worktree", "Git Worktree：并行隔离的工作目录", new String[]{},
                CommandType.LOCAL, "/worktree [list|create <name>|enter <name>|exit|remove <name>]",
                "用法：/worktree list · /worktree create <名字> · /worktree enter <名字> · "
                        + "/worktree exit · /worktree remove <名字> [--discard-changes]",
                this::handleWorktree));

        var names = AgentLoader.listNames(loaded.definitions());
        agentStatus = Styles.ACCENT.render("✻ 子 Agent：" + names.size() + " 个可用（"
                + String.join("、", names) + "）");
        if (!agentErrors.isEmpty()) {
            agentStatus += "\n" + Styles.ERROR.render("⚠ 有 " + agentErrors.size()
                    + " 个 Agent 定义文件解析失败（/tasks 看详情）");
        }

        commands.register(new com.agentjava.command.Command(
                "tasks", "子 Agent：可用类型 + 后台任务 + 调用链", new String[]{"agents"},
                CommandType.LOCAL, "/tasks", "", this::handleTasks));
        commands.register(new com.agentjava.command.Command(
                "task", "子 Agent 任务管理：info / cancel", new String[]{},
                CommandType.LOCAL, "/task info <任务 ID>",
                "用法：/task info <任务 ID> · /task cancel <任务 ID>", this::handleTask));
    }

    /** {@code /tasks}：可用 Agent 类型 + 后台任务列表 + 调用链。 */
    private String handleTasks(CommandContext ctx) {
        if (taskManager == null) {
            return "（子 Agent 系统未初始化）";
        }
        var sb = new StringBuilder();
        var tasks = taskManager.listTasks();
        if (tasks.isEmpty()) {
            sb.append("（还没有启动过子 Agent）\n\n")
                    .append("用 Agent 工具派发子任务；传 run_in_background: true 让它在后台跑，\n")
                    .append("留空 subagent_type 走 Fork 路径（继承当前对话，总是后台执行）。\n");
        } else {
            sb.append("后台任务（").append(tasks.size()).append(" 个，")
                    .append(taskManager.runningCount()).append(" 个在跑）\n\n");
            for (var t : tasks) {
                sb.append("  ").append(pad(t.id(), 12))
                        .append(pad(taskStatusLabel(t.status()), 10))
                        .append(t.name()).append('\n');
                if (t.backgrounded()) {
                    sb.append("      （已被 ESC 切到后台）\n");
                }
                if (t.error() != null && !t.error().isBlank()) {
                    sb.append("      错误：").append(t.error()).append('\n');
                }
            }
            sb.append("\n/task info <任务 ID> 看详情 · /task cancel <任务 ID> 取消");
        }

        var defs = agentTool == null ? Map.<String, com.agentjava.subagent.AgentDefinition>of()
                : agentTool.getAgentDefinitions();
        if (!defs.isEmpty()) {
            sb.append("\n\n可用 subagent_type（项目级 > 用户级 > 内置级 > 插件级）：\n");
            for (String name : AgentLoader.listNames(defs)) {
                var def = defs.get(name);
                sb.append("  ").append(pad(name, 20)).append(def.whenToUse()).append('\n');
            }
            sb.append("  留空则走 Fork 路径");
        }

        if (traceRegistry != null && !traceRegistry.summary().isEmpty()) {
            sb.append("\n\n调用链：").append(traceRegistry.summary()).append('\n')
                    .append(traceRegistry.render()).append('\n');
        }
        if (!agentErrors.isEmpty()) {
            sb.append("\n\n定义文件问题（").append(agentErrors.size()).append("）：\n");
            for (String e : agentErrors) {
                sb.append("  ✗ ").append(e).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /** {@code /task info <id>} · {@code /task cancel <id>}。 */
    private String handleTask(CommandContext ctx) {
        if (taskManager == null) {
            return "（子 Agent 系统未初始化）";
        }
        String[] parts = splitOnce(ctx.args());
        return switch (parts[0]) {
            case "info" -> taskInfo(parts[1]);
            case "cancel", "stop" -> taskCancel(parts[1]);
            default -> "未知子命令：" + parts[0]
                    + "\n用法：/task info <任务 ID> · /task cancel <任务 ID>";
        };
    }

    private String taskInfo(String rawId) {
        String id = rawId == null ? "" : rawId.strip();
        if (id.isEmpty()) {
            return "用法：/task info <任务 ID>（列表见 /tasks）";
        }
        var task = taskManager.getTask(id);
        if (task == null) {
            return "✗ 找不到任务: " + id;
        }
        var sb = new StringBuilder();
        sb.append("任务 ").append(task.id()).append(" · ").append(task.agentType()).append('\n');
        sb.append("  名称：").append(task.name()).append('\n');
        sb.append("  状态：").append(taskStatusLabel(task.status()));
        if (task.backgrounded()) {
            sb.append("（已转后台）");
        }
        sb.append('\n');
        sb.append("  耗时：").append(formatMillis(
                (task.endMillis() == null ? System.currentTimeMillis() : task.endMillis())
                        - task.startMillis())).append('\n');
        if (task.error() != null && !task.error().isBlank()) {
            sb.append("  错误：").append(task.error()).append('\n');
        }
        if (task.output() != null && !task.output().isBlank()) {
            sb.append("  输出：").append(previewResult(task.output())).append('\n');
        }
        if (traceRegistry != null && traceRegistry.get(id) != null) {
            sb.append("\n调用链：\n").append(traceRegistry.render());
        }
        return sb.toString().stripTrailing();
    }

    private String taskCancel(String rawId) {
        String id = rawId == null ? "" : rawId.strip();
        if (id.isEmpty()) {
            return "用法：/task cancel <任务 ID>（列表见 /tasks）";
        }
        return taskManager.cancelTask(id)
                ? Styles.ACCENT.render("✻ 已取消任务 " + id)
                : "✗ 取消失败: " + id + "（不存在或已经结束）";
    }

    /**
     * 把后台任务的完成通知注入对话。
     *
     * <p>走 messages 而不是 System Prompt，理由跟 Hook 通知一样：不让 System Prompt
     * 跟着变，Prompt Cache 的前缀才不会失效。顺带把链路追踪的最终状态回填——
     * 后台任务没有人在同步等它，只有这里能拿到它的结局。
     */
    private void flushTaskNotifications() {
        if (taskManager == null) {
            return;
        }
        var notes = taskManager.drainNotifications();
        if (notes.isEmpty()) {
            return;
        }
        var sb = new StringBuilder("后台子 Agent 任务有更新：\n");
        for (var n : notes) {
            if (traceRegistry != null && traceRegistry.get(n.taskId()) != null) {
                traceRegistry.finish(n.taskId(), TraceRegistry.fromTaskStatus(n.status()));
            }
            sb.append("<task-notification>\n")
                    .append("任务 ").append(n.taskId())
                    .append(" · ").append(n.agentType())
                    .append(" · ").append(taskStatusLabel(n.status())).append('\n')
                    .append(n.output() == null || n.output().isBlank() ? "（无文本输出）" : n.output())
                    .append("\n</task-notification>\n");
        }
        conversation.addSystemReminder(sb.toString());
    }

    /**
     * ESC：把正在前台等待的子 Agent 切到后台。
     *
     * <p>中断的只是「等待」那一条线程，worker 虚拟线程继续跑完，结果仍会回传。
     * 没有正在等待的任务时 ESC 什么都不做——它不承担「中断当前回复」的职责。
     */
    private Command handleEscape() {
        if (taskManager == null) {
            return Command.none();
        }
        String id = taskManager.switchForegroundToBackground();
        if (id == null) {
            return Command.none();
        }
        return Command.println(Styles.ACCENT.render("✻ 子 Agent 任务 " + id
                + " 已转入后台，结果会通过 task-notification 回传（/task info " + id + " 查看）"));
    }

    private static String taskStatusLabel(SubAgentTaskManager.TaskStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case PENDING -> "排队中";
            case RUNNING -> "运行中";
            case COMPLETED -> "已完成";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
        };
    }

    private static String formatMillis(long millis) {
        return millis < 1000 ? millis + "ms" : String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    // ───────────────────────── Hook 装配 ─────────────────────────

    /**
     * 启动时装配 Hook 系统：加载 → 逐条校验 → 注册 → 登记 {@code /hooks} 命令。
     *
     * <p><b>坏规则单独丢弃，不影响其它规则</b>：配置文件是人手写的，一处笔误不该让整个
     * Hook 系统失效，更不该让 Agent 起不来。解析失败和校验失败都只是跳过那一条，
     * 并攒进 {@link #hookErrors} 等 {@code /hooks} 展示。
     *
     * <p>agent 类型的执行器这里<b>不注册</b>：这里只搭接口与骨架，真实子 Agent 运行时
     * 交给 SubAgent。未注册时执行器返回一句明确说明，而不是抛异常。
     */
    private void bootstrapHooks() {
        hookEngine = new HookEngine();
        hookEngine.setWorkDir(workingDir);
        hookEngine.setLogger(msg -> {
            hookLog.add(msg);
            if (hookLog.size() > 30) {
                hookLog.remove(0);
            }
        });

        var loaded = HookConfig.load(workingDir);
        List<Hook> valid = new ArrayList<>();
        for (int i = 0; i < loaded.hooks().size(); i++) {
            Hook h = loaded.hooks().get(i);
            List<String> errs = HookEngine.validate(h, "hooks[" + i + "]");
            if (errs.isEmpty()) {
                valid.add(h);
            } else {
                hookErrors.addAll(errs);
            }
        }
        hookErrors.addAll(loaded.errors());
        hookEngine.registerAll(valid);

        // agent 类型动作的落地：接口此前只留骨架，这里接上真实的子 Agent 运行时
        if (agentTool != null) {
            hookEngine.setAgentRunner((prompt, ctx) -> agentTool.runHookAgent(prompt));
        }

        if (!valid.isEmpty()) {
            hookStatus = Styles.ACCENT.render("✻ 已加载 " + valid.size() + " 条 Hook（/hooks 查看）");
        }
        if (!hookErrors.isEmpty()) {
            hookStatus = (hookStatus.isEmpty() ? "" : hookStatus + "\n")
                    + Styles.ERROR.render("⚠ Hook 配置有 " + hookErrors.size()
                    + " 处问题，相关规则已跳过（/hooks 看详情）");
        }

        commands.register(new com.agentjava.command.Command(
                "hooks", "Hook 管理：查看已加载的生命周期钩子", new String[]{},
                CommandType.LOCAL, "/hooks", "", this::handleHooks));
    }

    /** 触发一个通知型事件；没有 Hook 引擎时是空操作。 */
    private List<HookResult> fireHook(HookContext ctx) {
        if (hookEngine == null) {
            return List.of();
        }
        return hookEngine.runHooks(ctx);
    }

    /**
     * 把本轮攒下的 prompt 类 Hook 输出注入对话。
     *
     * <p>格式是 {@code <system-reminder>} 包一层、里面每条一个
     * {@code <hook-notification>}。走 messages 而不是 System Prompt，
     * 是为了不让 System Prompt 跟着项目变——它一变，Prompt Cache 的整段前缀就失效了。
     */
    private void flushHookNotifications() {
        if (hookEngine == null) {
            return;
        }
        var results = hookEngine.drainNotifications();
        List<String> prompts = new ArrayList<>();
        for (HookResult r : results) {
            if (r.isPromptNotification()) {
                prompts.add(r.output().strip());
            }
        }
        if (prompts.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("以下提示来自生命周期 Hook，请纳入考虑：\n");
        for (String p : prompts) {
            sb.append("<hook-notification>\n").append(p).append("\n</hook-notification>\n");
        }
        conversation.addSystemReminder(sb.toString());
    }

    /** {@code /hooks}：列出已加载的 Hook 与配置问题。 */
    private String handleHooks(CommandContext ctx) {
        if (hookEngine == null) {
            return "（Hook 系统未初始化）";
        }
        var hooks = hookEngine.hooks();
        StringBuilder sb = new StringBuilder();
        if (hooks.isEmpty()) {
            sb.append("（还没有配置任何 Hook）\n\n把 hooks 写进下面任一文件即可自动加载：\n")
                    .append("  项目级：").append(Path.of(workingDir, HookConfig.PROJECT_PATH)).append('\n')
                    .append("  用户级：").append(HookConfig.globalPath()).append('\n')
                    .append("  本地级：").append(Path.of(workingDir, HookConfig.LOCAL_PATH))
                    .append("（不进 git，放本地临时改动）\n\n")
                    .append("三处配置是<b>追加合并</b>的，不是找到一个就停。");
        } else {
            sb.append("已加载 Hook（").append(hooks.size()).append(" 条）\n\n");
            for (Hook h : hooks) {
                sb.append("  ").append(pad(h.event().value(), 20))
                        .append(pad(h.label(), 22))
                        .append(pad(h.source().isEmpty() ? "-" : h.source(), 8));
                sb.append(h.action() == null ? "（缺 action）" : h.action().type().value());
                sb.append(h.reject() ? "  reject" : "");
                sb.append(h.once() ? "  once" : "");
                sb.append(h.async() ? "  async" : "");
                sb.append('\n');
                sb.append("      if: ").append(h.condition().display()).append('\n');
                sb.append("      →  ").append(h.action() == null ? "" : h.action().summary()).append('\n');
            }
            sb.append("\n声明顺序即执行顺序：pre_tool_use 上第一个命中的 reject 会短路后面的规则。");
        }
        if (!hookErrors.isEmpty()) {
            sb.append("\n\n配置问题（").append(hookErrors.size()).append("）：\n");
            for (String e : hookErrors) {
                sb.append("  ✗ ").append(e).append('\n');
            }
        }
        if (!hookLog.isEmpty()) {
            sb.append("\n\n运行日志（最近 ").append(hookLog.size()).append(" 条，Hook 出错不影响主流程）：\n");
            for (String line : hookLog) {
                sb.append("  · ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** 程序退出时调用：补一次 session_end + shutdown。 */
    public void fireShutdownHooks() {
        if (hookEngine == null) {
            return;
        }
        hookEngine.runHooks(HookContext.of(EventName.SESSION_END));
        hookEngine.runHooks(HookContext.of(EventName.SHUTDOWN));
        hookEngine.shutdown();
    }

    // ───────────────────────── SkillHost 实现 ─────────────────────────

    /**
     * Skill 宿主：inline 模式登记激活，fork 模式起一个隔离子 Agent。
     *
     * <p>子 Agent 与主循环共用工具注册中心（Skill 也要能读文件、跑命令），
     * 但对话是全新的：SOP 正文只出现在子对话里，主对话拿到的是它的最终结论。
     */
    private final class SkillHostImpl implements SkillForkHost {

        @Override
        public void activateSkill(String name, String body) {
            if (!activeSkills.contains(name)) {
                activeSkills.add(name);
            }
            recovery.recordSkill(name, body);
        }

        @Override
        public void recordSkillInvocation(String name, String body) {
            recovery.recordSkill(name, body);
        }

        @Override
        public List<com.agentjava.conversation.Message> snapshotParentMessages() {
            return List.copyOf(conversation.getMessages());
        }

        @Override
        public String runSubAgent(String body,
                                  List<com.agentjava.conversation.Message> seed,
                                  String model) {
            if (llmClient == null || activeProvider == null) {
                throw new IllegalStateException("还没有可用的 provider，无法启动隔离子 Agent");
            }
            // 只把纯文本消息带进子对话：工具调用块脱离了原来的 tool_result 配对关系，
            // 单独搬过去会破坏 user/assistant 交替与 tool_use 配对规则
            var subConv = new ConversationManager();
            if (seed != null) {
                for (var msg : seed) {
                    if (msg.getRole() == com.agentjava.conversation.Message.Role.USER) {
                        subConv.addUserMessage(msg.getContent());
                    } else {
                        subConv.addAssistantMessage(msg.getContent());
                    }
                }
            }
            subConv.addUserMessage(body);

            var agent = new Agent(clientForSkill(model), registry,
                    activeProvider.normalizedProtocol(), systemPrompt);
            agent.setWorkDir(workingDir);
            agent.setPermissionMode(permissionMode);
            agent.setMaxIterations(200);

            var output = new StringBuilder();
            BlockingQueue<AgentEvent> queue = agent.run(subConv);
            while (true) {
                AgentEvent event;
                try {
                    event = queue.poll(120, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Skill 子 Agent 被中断");
                }
                if (event == null) {
                    throw new IllegalStateException("Skill 子 Agent 等待事件超时");
                }
                if (event instanceof AgentEvent.StreamText st) {
                    output.append(st.text());
                } else if (event instanceof AgentEvent.ErrorEvent err) {
                    throw new IllegalStateException("Skill 子 Agent 失败：" + err.message());
                } else if (event instanceof AgentEvent.LoopComplete) {
                    return output.toString();
                }
            }
        }

        /**
         * Skill 声明了 {@code model} 且和当前 provider 不同时，临时换模型建一个客户端。
         * ProviderConfig 是 mutable 的，复制一份改 model 即可，不必改动原配置。
         */
        private LlmClient clientForSkill(String model) {
            if (model == null || model.isBlank() || model.equals(activeProvider.getModel())) {
                return llmClient;
            }
            var cfg = new ProviderConfig(activeProvider.getName(), activeProvider.getProtocol(),
                    activeProvider.getBaseUrl(), activeProvider.getApiKey(), model,
                    activeProvider.isThinking());
            cfg.setContextWindow(activeProvider.getContextWindow());
            return LlmClient.create(cfg, systemPrompt);
        }
    }

    /** Program 建好之后回灌进来，Agent 的事件要靠它投回事件循环。 */
    public void setProgram(Program program) {
        this.program = program;
    }

    /** 工具注册中心（对外只读用途：查有没有注册上、列当前工具集）。 */
    public ToolRegistry registry() {
        return registry;
    }

    // ───────────────────────── Model 接口实现 ─────────────────────────

    @Override
    public Command init() {
        return Command.none();
    }

    @Override
    public UpdateResult<? extends Model> update(Message msg) {
        if (msg instanceof WindowSizeMessage ws) {
            width = ws.width();
            height = ws.height();
            if (!bannerPrinted) {
                bannerPrinted = true;
                StringBuilder sb = new StringBuilder(renderBanner()).append("\n").append(renderReadyLine());
                if (!mcpStatus.isEmpty()) {
                    sb.append("\n").append(mcpStatus);
                }
                if (!hookStatus.isEmpty()) {
                    sb.append("\n").append(hookStatus);
                }
                if (!agentStatus.isEmpty()) {
                    sb.append("\n").append(agentStatus);
                }
                return UpdateResult.of(this, Command.println(sb.toString()));
            }
            return UpdateResult.of(this);
        }
        if (msg instanceof KeyPressMessage key) {
            return UpdateResult.of(this, handleKey(key));
        }
        if (msg instanceof AgentEventMessage aem) {
            return UpdateResult.of(this, handleAgentEvent(aem.event()));
        }
        return UpdateResult.of(this);
    }

    @Override
    public String view() {
        return state == AppState.PROVIDER_SELECT ? viewProviderSelect() : viewChat();
    }

    // ───────────────────────── 事件处理 ─────────────────────────

    private Command handleKey(KeyPressMessage key) {
        if ("ctrl+c".equals(key.key()) || "ctrl+d".equals(key.key())) {
            return Command.quit();
        }
        if (state == AppState.PROVIDER_SELECT) {
            return handleProviderSelectKey(key);
        }
        return handleChatKey(key);
    }

    private Command handleProviderSelectKey(KeyPressMessage key) {
        switch (key.key()) {
            case "up" -> selectedIndex = Math.max(0, selectedIndex - 1);
            case "down" -> selectedIndex = Math.min(providers.size() - 1, selectedIndex + 1);
            case "enter" -> {
                activateProvider(selectedIndex);
                state = AppState.CHAT;
            }
            default -> {
            }
        }
        return Command.none();
    }

    private Command handleChatKey(KeyPressMessage key) {
        // 权限确认弹窗优先于普通输入：此时 Agent Loop 阻塞在 future 上，等待用户拍板
        if (awaitingPermission) {
            return handlePermissionKey(key);
        }
        // ESC 先于 streaming 判定：流式期间唯一还该响应的键就是它（切子 Agent 到后台）
        if ("esc".equals(key.key())) {
            return handleEscape();
        }
        if (streaming) {
            return Command.none();
        }
        switch (key.key()) {
            case "enter" -> {
                return submit();
            }
            // 命令补全：/ 开头才生效，单个候选直接填上，多个候选列出来让用户挑
            case "tab" -> {
                return completeCommand();
            }
            // 多行编辑：Alt+Enter 与 Ctrl+J 都支持，不同终端发的序列不一样
            case "alt+enter", "ctrl+j" -> input.append('\n');
            case "backspace" -> {
                if (input.length() > 0) {
                    input.deleteCharAt(input.length() - 1);
                }
            }
            case "delete" -> input.setLength(0);
            case "ctrl+u" -> input.setLength(0);
            case "rune" -> input.append(key.rune());
            default -> {
            }
        }
        return Command.none();
    }

    /**
     * 提交一行输入。
     *
     * <p>分流的时机很关键：必须在消息发出去之前。以 {@code /} 开头的走命令系统，
     * 在本地执行、不消耗 token、行为确定；否则才进 Agent Loop。
     */
    private Command submit() {
        String text = input.toString();
        input.setLength(0);
        if (text.isBlank()) {
            return Command.none();
        }
        CommandParser.ParsedInput parsed = CommandParser.parse(text);
        if (!parsed.isCommand()) {
            return startAgentTurn(text);
        }
        // command_execute：Slash Command 执行前通知一声
        fireHook(HookContext.of(EventName.COMMAND_EXECUTE, text));
        return dispatchSlashCommand(parsed);
    }

    /**
     * 命令分发：解析 → 查找 → 按类型路由。
     *
     * <p>三个分支各有讲究：
     * <ul>
     *   <li><b>只输入了 {@code /}</b>：命令名为空。这不是错误，用户只是想看看有哪些命令，
     *       直接列清单比报错友好；</li>
     *   <li><b>找不到命令</b>：错误信息里必须带 {@code /help} ——错误提示永远带退出路径，
     *       这条原则贯穿整个 AgentJava；</li>
     *   <li><b>缺参数但命令声明了 argPrompt</b>：直接显示那段提示，比「参数缺失」有用得多。</li>
     * </ul>
     *
     * <p>之后按 {@link CommandType} 路由：local / local-ui 的返回值是给人看的文本，
     * prompt 类型的返回值是一段要发给 LLM 的 prompt。
     */
    Command dispatchSlashCommand(CommandParser.ParsedInput parsed) {
        if (parsed.name().isEmpty()) {
            return printCommandList();
        }
        Optional<com.agentjava.command.Command> found = commands.find(parsed.name());
        if (found.isEmpty()) {
            return Command.println(Styles.ERROR.render(
                    "✗ 未知命令：/" + parsed.name() + "，输入 /help 查看可用命令"));
        }
        com.agentjava.command.Command cmd = found.get();
        if (parsed.args().isEmpty() && !cmd.argPrompt().isBlank()) {
            return Command.println(Styles.MUTED.render(cmd.argPrompt()));
        }
        return switch (cmd.type()) {
            // 有 handler 的 local-ui 命令（plan/do）同样走这里，差别只在跑完之后刷新一次状态栏
            case LOCAL, PROMPT, LOCAL_UI -> cmd.hasHandler()
                    ? runHandlerCommand(cmd, parsed.args())
                    : runUiCommand(cmd, parsed.args());
        };
    }

    /** 有 handler 的命令：拼上下文、执行、按类型处理返回值。 */
    private Command runHandlerCommand(com.agentjava.command.Command cmd, String args) {
        String out;
        try {
            out = cmd.handler().handle(buildCommandContext(args));
        } catch (RuntimeException e) {
            // 命令出错不该把整个 TUI 带崩：兜成一条错误输出，用户可以接着用
            out = Styles.ERROR.render("✗ 命令执行失败：" + e.getMessage());
        }
        if (cmd.type() == CommandType.LOCAL_UI) {
            refreshStatus();
        }
        if (cmd.type() == CommandType.PROMPT) {
            // prompt 类型的返回值不是展示文本，是要发给 LLM 的提示词
            Command printed = emitPendingAnd(null);
            Command turn = startAgentTurn(out);
            return printed instanceof Command.None ? turn : Command.batch(printed, turn);
        }
        return emitThenMaybeRunAgent(out);
    }

    /**
     * 没有 handler 的 local-ui 命令兜底实现。
     *
     * <p>它们要动的东西（LLM 客户端、会话存档、整个会话状态）只有宿主层才握得住，
     * 硬塞进 {@link CommandContext} 只会让那个「背包」退化成到处传的 god object。
     */
    private Command runUiCommand(com.agentjava.command.Command cmd, String args) {
        refreshStatus();
        return switch (cmd.name()) {
            case "exit" -> Command.quit();
            case "compact" -> emitThenMaybeRunAgent(compactNow(args));
            case "clear" -> emitThenMaybeRunAgent(startNewSession());
            case "mode" -> emitThenMaybeRunAgent(switchPermissionMode(args));
            case "resume" -> emitThenMaybeRunAgent(resumeSession(args));
            default -> emitPendingAnd(Styles.MUTED.render("（/" + cmd.name() + " 还没有接进来）"));
        };
    }

    /**
     * Tab 补全候选。
     *
     * <p>Skill 注册进 {@link CommandRegistry} 之后，补全不用为它单独写一套匹配——
     * 它跟内置命令在同一个候选池里，用户敲 {@code /comm} 就能补全出 {@code /commit}。
     */
    List<String> commandCandidates(String raw) {
        if (raw == null || !raw.startsWith("/") || raw.indexOf(' ') >= 0) {
            return List.of();
        }
        return commands.search(raw);
    }

    /** Tab 补全：输入框以 {@code /} 且不含空格时才生效。 */
    private Command completeCommand() {
        String raw = input.toString();
        // 已经输过参数就不再补全——参数候选（会话 ID 之类）暂未实现
        List<String> candidates = commandCandidates(raw);
        if (candidates.isEmpty()) {
            return Command.none();
        }
        if (candidates.size() == 1) {
            input.setLength(0);
            input.append(candidates.get(0));
            return Command.none();
        }
        StringBuilder sb = new StringBuilder(Styles.ACCENT.render("候选命令："));
        for (String candidate : candidates) {
            sb.append(' ').append(candidate);
        }
        return Command.println(sb.toString());
    }

    /** {@code /} 或 {/help} 的清单输出：清单本身也是从注册中心里查出来的，不硬编码。 */
    private Command printCommandList() {
        String text = commands.handlerOf("help")
                .map(h -> h.handle(buildCommandContext("")))
                .orElse("（注册中心里没有可用的 /help）");
        return Command.println(text);
    }

    /**
     * 打印命令的输出 + {@link UIController#addSystemMessage} 期间攒下的所有系统消息，
     * 然后看命令有没有顺手投递一条用户消息（{@code /plan <任务>} 就是这么工作的）。
     */
    private Command emitThenMaybeRunAgent(String text) {
        String follow = pendingUserMessage;
        pendingUserMessage = null;
        Command printed = emitPendingAnd(text);
        if (follow == null || follow.isBlank()) {
            return printed;
        }
        Command turn = startAgentTurn(follow);
        return printed instanceof Command.None ? turn : Command.batch(printed, turn);
    }

    /** 把沉淀的系统消息外加一行正文一起打出去。 */
    private Command emitPendingAnd(String text) {
        List<String> lines = new ArrayList<>(pendingSystemMessages);
        pendingSystemMessages.clear();
        if (text != null && !text.isBlank()) {
            lines.add(text);
        }
        if (lines.isEmpty()) {
            return Command.none();
        }
        return Command.println(String.join("\n", lines));
    }

    /**
     * 把一段文本作为用户输入交给 Agent：命令系统转过来的 prompt 与普通输入共用这条路径，
     * 保证历史落盘、工具链修复、会话存档这几件事对所有来路一视同仁。
     */
    private Command startAgentTurn(String text) {
        // 每轮开工前先看一眼 Skill 目录：用户可能刚往里丢了一个新 Skill
        refreshSkillsIfNeeded();
        // turn_start：一轮对话开始的节点
        fireHook(HookContext.of(EventName.TURN_START, text));
        // 用户输入先写进 scrollback，再进历史：即使请求失败也能看到自己问了什么
        Command echo = Command.println(Styles.PROMPT.render("❯ ") + Styles.USER.render(text));
        conversation.addUserMessage(text);
        history.append(text);
        // 发请求前统一兜一层：修复可能残缺的工具调用链。
        // 会话恢复只是它要兜住的来路之一，所以要放在每轮提交时而不是只在 /resume 里。
        conversation.fixToolCallChain();
        flushSession();

        if (llmClient == null) {
            return Command.batch(echo, Command.println(Styles.ERROR.render("✗ 还没有可用的 provider。")));
        }

        streaming = true;
        streamBuf.setLength(0);
        agentErrored = false;
        spinnerVerb = SpinnerVerbs.random();
        requestStartMillis = System.currentTimeMillis();

        // 把活儿交给 Agent：它在虚拟线程里跑 ReAct 主循环，事件回投到本事件循环
        Agent agent = new Agent(llmClient, registry, activeProvider.normalizedProtocol(), systemPrompt);
        agent.setPlanMode(planMode);
        agent.setWorkDir(currentWorkDir());
        agent.setPermissionMode(permissionMode);
        agent.setMaxIterations(200);
        agent.setContextWindow(contextWindow);
        agent.setSessionId(sessionId);
        agent.setRecovery(recovery);
        agent.setBreaker(breaker);
        agent.setHookEngine(hookEngine);
        // 子 Agent 每轮都要重新对齐这几样：会话 ID 会随 /clear 变，权限模式会随 /mode 变，
        // Hook 引擎在引擎装配完成前是 null
        if (agentTool != null) {
            agentTool.setSessionId(sessionId);
            agentTool.setPermissionMode(permissionMode);
            agentTool.setHookEngine(hookEngine);
            agentTool.setWorkDir(currentWorkDir());
        }
        BlockingQueue<AgentEvent> queue = agent.run(conversation);
        Thread.startVirtualThread(() -> pumpAgentEvents(queue));
        return echo;
    }

    /**
     * 组装命令上下文。
     *
     * <p>全部按需包成 Supplier：provider 可能还没激活、工具数量可能因为 MCP 接入而变化，
     * 用函数式取值保证命令读到的永远是「当下」，而不是建 ctx 那一刻的快照。
     */
    /**
     * 当前生效的工作目录：已进入 Worktree 时用 Worktree 路径，否则用项目根目录。
     * 主 Agent 与子 Agent 都从它取 cwd（工具再据此显式解析路径），不改进程级 cwd。
     */
    private String currentWorkDir() {
        return activeWorktree != null ? activeWorktree : workingDir;
    }

    /**
     * 启动时尝试恢复上次会话里已进入的 Worktree（--resume 支持）。
     * 仅当会话文件存在且对应目录仍在时才恢复；否则清掉陈旧的会话文件。
     */
    private void tryRestoreWorktreeSession() {
        try {
            var opt = WorktreeSessionStore.load(workingDir);
            if (opt.isEmpty()) {
                return;
            }
            WorktreeSession s = opt.get();
            if (s.isActive() && Files.isDirectory(Path.of(s.worktreePath()))) {
                activeWorktree = s.worktreePath();
            } else {
                WorktreeSessionStore.save(workingDir, null);
            }
        } catch (Exception ignored) {
            // 恢复失败不阻断启动
        }
    }

    /** {@code /worktree}：Git Worktree 的 list / create / enter / exit / remove 五个子命令。 */
    private String handleWorktree(CommandContext ctx) {
        if (worktreeManager == null) {
            return "（Worktree 模块未初始化）";
        }
        String args = ctx.args() == null ? "" : ctx.args().strip();
        if (args.isEmpty()) {
            return worktreeHelp();
        }
        String[] parts = args.split("\\s+", 2);
        String sub = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].strip() : "";

        switch (sub) {
            case "list" -> {
                var list = worktreeManager.list();
                if (list.isEmpty()) {
                    return "（还没有 Worktree；用 /worktree create <名字> 创建一个）";
                }
                var sb = new StringBuilder("Worktree 列表（").append(list.size()).append(" 个）：\n");
                for (var w : list) {
                    sb.append("  ").append(w.path())
                            .append(w.branch().isBlank() ? "" : "  [" + w.branch() + "]")
                            .append('\n');
                }
                if (activeWorktree != null) {
                    sb.append("\n当前已进入：").append(activeWorktree).append('\n');
                }
                return sb.toString();
            }
            case "create" -> {
                if (rest.isEmpty()) {
                    return "用法：/worktree create <名字>（名字会同时用作目录名和分支名 worktree-<名字>）";
                }
                try {
                    var info = worktreeManager.create(rest, "HEAD");
                    return "已创建 Worktree：\n  路径：" + info.path() + "\n  分支：" + info.branch()
                            + "\n（手动创建的 Worktree 不会被自动清理；用 /worktree enter 切换过去，"
                            + "或 /worktree remove 删除）";
                } catch (IllegalArgumentException e) {
                    return Styles.ERROR.render("✗ " + e.getMessage());
                }
            }
            case "enter" -> {
                if (rest.isEmpty()) {
                    return "用法：/worktree enter <名字>";
                }
                String flat = com.agentjava.worktree.SlugValidator.flatten(rest);
                Path target = worktreeManager.worktreeDir().resolve(flat).toAbsolutePath().normalize();
                if (!Files.isDirectory(target)) {
                    return Styles.ERROR.render("✗ Worktree 不存在："
                            + rest + "（请先用 /worktree create 创建）");
                }
                String branch = com.agentjava.worktree.SlugValidator.branchName(rest);
                String origBranch = git(workingDir, "rev-parse", "--abbrev-ref", "HEAD");
                String origHead = git(workingDir, "rev-parse", "HEAD");
                var session = new WorktreeSession(workingDir, target.toString(), rest, branch,
                        origBranch == null ? "" : origBranch,
                        origHead == null ? "" : origHead, sessionId, 0);
                try {
                    WorktreeSessionStore.save(workingDir, session);
                } catch (java.io.IOException e) {
                    return Styles.ERROR.render("✗ 会话持久化失败：" + e.getMessage());
                }
                activeWorktree = target.toString();
                return "已进入 Worktree「" + rest + "」→ " + target
                        + "\n（后续文件操作都发生在隔离副本里；/worktree exit 退出）";
            }
            case "exit" -> {
                if (activeWorktree == null) {
                    return "（当前不在任何 Worktree 里）";
                }
                try {
                    WorktreeSessionStore.save(workingDir, null);
                } catch (java.io.IOException e) {
                    return Styles.ERROR.render("✗ 会话清除失败：" + e.getMessage());
                }
                activeWorktree = null;
                return "已退出 Worktree，回到主目录：" + workingDir;
            }
            case "remove" -> {
                if (rest.isEmpty()) {
                    return "用法：/worktree remove <名字> [--discard-changes]（有未提交修改时需加 --discard-changes）";
                }
                boolean discard = rest.contains("--discard-changes");
                String name = rest.replace("--discard-changes", "").strip();
                try {
                    worktreeManager.remove(name, "remove", discard);
                    return "已删除 Worktree：" + name;
                } catch (IllegalArgumentException e) {
                    return Styles.ERROR.render("✗ " + e.getMessage());
                } catch (IllegalStateException e) {
                    return Styles.ERROR.render("✗ " + e.getMessage()
                            + "（加 --discard-changes 可强制删除）");
                }
            }
            default -> {
                return Styles.ERROR.render("✗ 未知子命令：/worktree " + sub + "\n") + worktreeHelp();
            }
        }
    }

    /** 在指定 cwd 下跑一条 git 命令，返回 stdout（失败返回 null）。 */
    private String git(String cwd, String... args) {
        return com.agentjava.worktree.GitUtil.run(cwd, args);
    }

    /** /worktree 的用法说明。 */
    private String worktreeHelp() {
        return "Git Worktree 子命令：\n"
                + "  /worktree list                       列出所有 Worktree\n"
                + "  /worktree create <名字>              新建一个 Worktree（不会被自动清理）\n"
                + "  /worktree enter <名字>               切换到该 Worktree 工作\n"
                + "  /worktree exit                       退出当前 Worktree，回到主目录\n"
                + "  /worktree remove <名字> [--discard-changes]  删除 Worktree";
    }

    private CommandContext buildCommandContext(String args) {
        return new CommandContext(
                args == null ? "" : args,
                this,
                workingDir,
                activeProvider == null ? "-" : activeProvider.getModel(),
                VERSION,
                sessionId,
                () -> permissionMode,
                () -> registry.listTools().size(),
                () -> contextWindow,
                memory::loadUserMemories,
                memory::loadProjectMemories,
                memory::clearAll,
                () -> SessionManager.listSessions(workingDir));
    }

    /** {@code /compact [保留重点]}：低于下限就直接告诉用户不必压缩，别白跑一次 LLM。 */
    private String compactNow(String focus) {
        if (llmClient == null) {
            return Styles.ERROR.render("✗ 还没有可用的 provider。");
        }
        int before = ContextCompactor.estimateTokens(conversation.getMessages());
        if (before < MIN_COMPACT_TOKENS) {
            return Styles.MUTED.render("✻ 上下文只有 " + before + " tokens，无需压缩（阈值 "
                    + MIN_COMPACT_TOKENS + "）");
        }
        List<Map<String, Object>> schemas =
                registry.getAllSchemas(activeProvider.normalizedProtocol());
        try {
            ContextCompactor.forceCompact(
                    conversation, llmClient, contextWindow, workingDir, sessionId,
                    recovery, schemas, focus == null ? "" : focus);
        } catch (RuntimeException e) {
            return Styles.ERROR.render("✗ 压缩失败：" + e.getMessage());
        }
        // 压缩要把 marker 落到会话存档里，否则下次恢复只能全量回放被压缩掉的历史
        writeCompactBoundary();
        fireHook(HookContext.of(EventName.COMPACT));
        int after = ContextCompactor.estimateTokens(conversation.getMessages());
        return Styles.ACCENT.render("✻ 已压缩: " + before + " → " + after + " estimated tokens");
    }

    /**
     * {@code /clear}：旧会话已经增量落盘，这里只要换个新 ID 重开。
     *
     * <p>旧的对话不会丢，随时能用 {@code /session list} 找到、用 {@code /resume} 回去。
     */
    private String startNewSession() {
        // session_end：旧会话结束；清空之后再补一次 session_start
        fireHook(HookContext.of(EventName.SESSION_END));
        flushHookNotifications();
        flushSession();
        conversation.clear();
        sessionId = SessionManager.newId();
        persistedCount = 0;
        // 换会话 = 换上下文，激活过的 Skill 也一起清掉；否则压缩恢复块会把上一轮会话的
        // SOP 带进一个跟它毫无关系的对话里
        activeSkills.clear();
        recovery.clearSkills();
        // 重新装配一次记忆索引：清空后对话为空，注入才会发生
        bootstrapMemory();
        // once 的语义是「本次会话只触发一次」，换了会话就该重新算
        if (hookEngine != null) {
            hookEngine.resetExecuted();
        }
        fireHook(HookContext.of(EventName.SESSION_START));
        flushHookNotifications();
        persistedCount = conversation.size();
        usageAvailable = false;
        lastInputTokens = 0;
        lastOutputTokens = 0;
        return Styles.ACCENT.render("✻ 已开启新会话 " + sessionId)
                + Styles.MUTED.render("（旧会话已存档，/session list 查看 · /resume <id> 回去）");
    }

    /** {@code /mode <模式>}：切换权限模式（default / accept / plan / yolo）。 */
    private String switchPermissionMode(String arg) {
        return switch (arg == null ? "" : arg.strip().toLowerCase(Locale.ROOT)) {
            case "default" -> {
                applyPlanMode(false);
                permissionMode = PermissionMode.DEFAULT;
                yield Styles.ACCENT.render("✻ 权限模式: default（只读自动放行，写/命令需确认）");
            }
            case "accept", "acceptedits", "accept-edits" -> {
                applyPlanMode(false);
                permissionMode = PermissionMode.ACCEPT_EDITS;
                yield Styles.ACCENT.render("✻ 权限模式: acceptEdits（写文件自动放行，命令需确认）");
            }
            case "plan" -> {
                permissionMode = PermissionMode.PLAN;
                applyPlanMode(true);
                yield Styles.ACCENT.render("✻ 权限模式: plan（仅规划不改动）");
            }
            case "yolo", "bypass" -> {
                applyPlanMode(false);
                permissionMode = PermissionMode.BYPASS;
                yield Styles.ERROR.render("⚠ 权限模式: bypass（跳过全部确认，仅黑名单生效，危险！）");
            }
            default -> Styles.ERROR.render("用法: /mode default|accept|plan|yolo");
        };
    }

    /**
     * Plan Mode 切换：工具可用性（planMode 字段）与 System Prompt 提醒要一起动。
     *
     * <p>已经是目标状态时直接返回——提醒注入是给模型看的「上下文断点」，
     * 重复注入只会让对话里堆一堆同义重复。
     */
    private void applyPlanMode(boolean enabled) {
        if (planMode == enabled) {
            return;
        }
        String planPath = PlanModePrompt.getPlanPath(workingDir);
        if (enabled) {
            planMode = true;
            permissionMode = PermissionMode.PLAN;
            conversation.addSystemReminder(PlanModePrompt.buildReentryReminder(planPath));
        } else {
            planMode = false;
            permissionMode = PermissionMode.DEFAULT;
            conversation.addSystemReminder(
                    PlanModePrompt.buildExitReminder(planPath, PlanModePrompt.planExists(planPath)));
        }
    }

    // ───────────────────────── UIController 实现 ─────────────────────────

    @Override
    public void addSystemMessage(String text) {
        pendingSystemMessages.add(text);
    }

    @Override
    public void sendUserMessage(String text) {
        pendingUserMessage = text;
    }

    @Override
    public void setPlanMode(boolean enabled) {
        applyPlanMode(enabled);
    }

    /** 当前上下文估算 token 数。命令要展示的就是界面上展示的同一个数字。 */
    @Override
    public int getTokenCount() {
        return ContextCompactor.estimateTokens(conversation.getMessages());
    }

    /** Bubble Tea 每帧重绘视图，状态栏自然会更新，所以这里没有要刷的东西。 */
    @Override
    public void refreshStatus() {
        // 契约保留：换成非每帧重绘的 UI 时，这里要真正触发一次重绘
    }

    /** 用户在权限确认弹窗下的按键处理：y 允许 / a 始终允许 / n 拒绝。 */
    private Command handlePermissionKey(KeyPressMessage key) {
        String ch = "rune".equals(key.key()) ? String.valueOf(key.rune()) : key.key();
        PermissionResponse resp;
        if ("y".equalsIgnoreCase(ch)) {
            resp = PermissionResponse.ALLOW;
        } else if ("a".equalsIgnoreCase(ch)) {
            resp = PermissionResponse.ALLOW_ALWAYS;
        } else if ("n".equalsIgnoreCase(ch)) {
            resp = PermissionResponse.DENY;
        } else {
            return Command.none();
        }
        var fut = this.pendingPermission;
        this.awaitingPermission = false;
        this.pendingPermission = null;
        if (fut != null) {
            fut.complete(resp);
        }
        String label = switch (resp) {
            case ALLOW -> "允许";
            case ALLOW_ALWAYS -> "始终允许（已写入本地规则）";
            case DENY -> "拒绝";
        };
        return Command.println(Styles.ACCENT.render("✻ " + label));
    }

    /** 把 Agent 事件逐个投回事件循环；遇到终止事件就收工。 */
    private void pumpAgentEvents(BlockingQueue<AgentEvent> queue) {
        try {
            while (true) {
                AgentEvent event = queue.take();
                if (program != null) {
                    program.send(new AgentEventMessage(event));
                }
                if (event instanceof AgentEvent.LoopComplete || event instanceof AgentEvent.ErrorEvent) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Command handleAgentEvent(AgentEvent event) {
        // 每个事件进来先落一次盘：Agent 是在它自己的虚拟线程里往 conversation 追加消息的，
        // 这里做 diff 增量写，崩溃时最多丢最后一条，而不是丢整个 Turn。
        flushSession();
        return switch (event) {
            // 正文增量进缓冲区，界面立刻就能看到
            case AgentEvent.StreamText st -> {
                streamBuf.append(st.text());
                yield Command.none();
            }
            // 工具调用：把"动手"了什么打到 scrollback
            case AgentEvent.ToolUseEvent tu ->
                    Command.println(Styles.TOOL.render("⏺ " + tu.toolName() + " " + previewArgs(tu.args())));
            // 工具结果：只显示首行预览
            case AgentEvent.ToolResultEvent tr ->
                    Command.println(Styles.MUTED.render("  ↳ " + previewResult(tr.output())));
            // 累计 token 用量，留着状态栏展示
            case AgentEvent.UsageEvent ue -> {
                lastInputTokens = ue.inputTokens();
                lastOutputTokens = ue.outputTokens();
                usageAvailable = true;
                yield Command.none();
            }
            case AgentEvent.TurnComplete tc -> Command.none();
            case AgentEvent.CompactingEvent ce -> {
                // 压缩留下了 marker：摘要 + 保留的尾部原文，供下次恢复做压缩感知重建
                writeCompactBoundary();
                yield Command.println(Styles.ACCENT.render(
                        "✻ 压缩上下文: " + ce.beforeTokens() + " → " + ce.afterTokens() + " estimated tokens"));
            }
            case AgentEvent.RetryEvent re ->
                    Command.println(Styles.MUTED.render("↻ " + re.reason()));
            // 权限请求：Agent Loop 已经阻塞在 future 上，这里弹出确认窗，等用户拍板
            case AgentEvent.PermissionRequestEvent pre -> {
                awaitingPermission = true;
                pendingPermission = pre.response();
                yield Command.println(
                        Styles.ACCENT.render("⚠ 权限请求: " + pre.description())
                                + Styles.MUTED.render("\n  (y) 允许 / (a) 始终允许 / (n) 拒绝"));
            }
            // 整个循环结束：先触发记忆系统（提取 / 治理），再把缓冲区落成最终回复
            case AgentEvent.LoopComplete lc -> {
                triggerMemoryExtraction();
                triggerMemoryConsolidation();
                yield finishTurn(streamBuf.toString());
            }
            // 出错：展示错误，程序不退出
            case AgentEvent.ErrorEvent ee -> failTurn(ee.message());
        };
    }

    /** 本轮正常结束：缓冲区渲染成 Markdown；历史消息由 Agent 负责落地。 */
    private Command finishTurn(String fullText) {
        if (agentErrored) {
            streaming = false;
            return Command.none();
        }
        long elapsed = System.currentTimeMillis() - requestStartMillis;
        streaming = false;
        streamBuf.setLength(0);
        // turn_end：一轮收尾，Hook 的 prompt 输出在这里取走并注入，下一轮 LLM 就能看到
        fireHook(HookContext.of(EventName.TURN_END));
        flushHookNotifications();
        // 后台子 Agent 的结论在这里进对话：下一轮 LLM 就能看到
        flushTaskNotifications();

        if (fullText.isBlank()) {
            return Command.println(Styles.MUTED.render("（本轮没有返回文本内容）"));
        }
        String rendered = MarkdownRenderer.render(fullText, Math.max(30, width - 2));
        String meta = Styles.MUTED.render("✻ " + spinnerVerb + " for " + formatSeconds(elapsed)
                + " · ↑" + lastInputTokens + " ↓" + lastOutputTokens);
        return Command.println(rendered + "\n" + meta);
    }

    /** 本轮失败：错误以独立样式展示，程序不退出，用户可以直接问下一句。 */
    private Command failTurn(String message) {
        streaming = false;
        agentErrored = true;
        String partial = streamBuf.toString();
        streamBuf.setLength(0);
        // 中断时已经收到的那部分照样落成消息，下一轮还能接着聊（历史由 Agent 管，
        // 这里只在 Agent 没落地时补一条，避免重复）
        if (!partial.isBlank() && conversation.getMessages().stream()
                .noneMatch(m -> partial.equals(m.getContent()))) {
            conversation.addAssistantMessage(partial);
        }
        // error + turn_end：出错也要收尾，否则 turn_end 上的 Hook 永远等不到这一轮
        fireHook(HookContext.of(EventName.ERROR, message));
        fireHook(HookContext.of(EventName.TURN_END));
        flushHookNotifications();
        flushTaskNotifications();
        return Command.println(Styles.ERROR.render("✗ " + message));
    }

    private void activateProvider(int index) {
        ProviderConfig cfg = providers.get(index);
        activeProvider = cfg;
        // 用真实 model 重建 System Prompt（Environment 段落会带上模型名），再建客户端
        this.systemPrompt = PromptBuilder.buildSystemPrompt(
                PromptBuilder.detectEnvironment(cfg.getModel()), null);
        this.contextWindow = cfg.getContextWindow();
        llmClient = LlmClient.create(cfg, systemPrompt);
        // 子 Agent 要用同一套客户端配置；模型别名（haiku 之类）由 modelResolver 按需另建
        if (agentTool != null) {
            agentTool.setClient(llmClient);
            agentTool.setProtocol(activeProvider.normalizedProtocol());
            agentTool.setBaseSystemPrompt(systemPrompt);
            agentTool.setModelResolver(this::clientForSubAgent);
        }
    }

    /**
     * 子 Agent 指定模型时临时另建一个客户端：复制一份 provider 配置改 model，
     * 不去动原配置（{@code ProviderConfig} 是可变的，复制最省事也最安全）。
     */
    private LlmClient clientForSubAgent(String model) {
        if (model == null || model.isBlank() || activeProvider == null) {
            return llmClient;
        }
        if (model.equals(activeProvider.getModel())) {
            return llmClient;
        }
        var cfg = new ProviderConfig(activeProvider.getName(), activeProvider.getProtocol(),
                activeProvider.getBaseUrl(), activeProvider.getApiKey(), model,
                activeProvider.isThinking());
        cfg.setContextWindow(activeProvider.getContextWindow());
        return LlmClient.create(cfg, systemPrompt);
    }

    // ───────────────────────── 会话持久化 ─────────────────────────

    /**
     * 把尚未落盘的对话增量追加到会话存档。
     *
     * <p>每条消息一次追加写：O(1)，且写到一半崩溃最多坏掉最后一行，
     * 前面的历史仍然能被完整解析出来。
     *
     * <p>指针可能越过 size——那是压缩重构了对话（历史变短）。
     * 这时以压缩边界为准推进指针即可，边界本身已经记下了压缩前的全部语义。
     */
    private void flushSession() {
        var messages = conversation.getMessages();
        if (persistedCount > messages.size()) {
            persistedCount = messages.size();
            return;
        }
        for (int i = persistedCount; i < messages.size(); i++) {
            SessionManager.saveConversationMessage(workingDir, sessionId, messages.get(i));
        }
        persistedCount = messages.size();
    }

    /**
     * 压缩发生后写一条边界记录。
     *
     * <p>压缩后的对话形如「一条摘要 user + 保留的尾部原文」——这是 Ch8 重建的定式，
     * 所以这里不必回头问压缩器要数据，直接从当前对话推出来就行：
     * 第 0 条是摘要，其余是尾巴。
     */
    private void writeCompactBoundary() {
        var messages = conversation.getMessages();
        if (messages.isEmpty()) {
            return;
        }
        String summary = messages.get(0).getContent();
        var keep = SessionManager.keepMessagesFrom(messages.subList(1, messages.size()));
        SessionManager.saveCompactBoundary(workingDir, sessionId, summary, keep);
        persistedCount = messages.size();
    }

    // ───────────────────────── 自动记忆 ─────────────────────────

    /**
     * 每轮 Loop 结束时甩一个虚拟线程去提取记忆。
     *
     * <p>{@code MemoryManager.extract} 本身是同步阻塞的，异步在这里解决——
     * 提取绝大部分时间是在等网络返回，占着一个平台线程纯属浪费。
     * 发出去就不再跟踪，没做「上一轮还在跑就补一轮」这类合并调度。
     */
    private void triggerMemoryExtraction() {
        if (llmClient == null || conversation.getMessages().isEmpty()) {
            return;
        }
        final var client = llmClient;
        final var conv = conversation;
        Thread.startVirtualThread(() -> {
            try {
                if (memory.shouldExtract()) {
                    memory.extract(client, conv);
                }
            } catch (Exception e) {
                // 提取跑在后台，失败不能冒泡到界面
            }
        });
    }

    /**
     * 治理与提取挂在同一个位置（Loop 完成时），但两者节奏完全不同：
     * 提取每轮都跑，治理每轮都调但内部门控会拦住绝大多数调用——
     * 只有攒够 24 小时和 5 个会话后才真正执行一次。
     */
    private void triggerMemoryConsolidation() {
        if (llmClient == null || activeProvider == null) {
            return;
        }
        try {
            consolidator.maybeRun(llmClient, conversation, activeProvider.normalizedProtocol());
        } catch (Exception e) {
            // 低频重量操作，失败静默：下轮还会再试
        }
    }

    // ───────────────────────── /resume：会话切换 ─────────────────────────

    /**
     * {@code /resume <id>}：回到历史会话。
     *
     * <p>入口检查放在这里而不是注册中心：能不能恢复取决于「当前是否正在流式输出」，
     * 这类 UI 状态只有宿主知道。
     */
    private String resumeSession(String id) {
        if (streaming) {
            return Styles.ERROR.render("✗ 正在回复中，请等本轮结束再切换会话");
        }
        if (id == null || id.isBlank()) {
            return Styles.ERROR.render("✗ 请带上会话 ID。历史会话列表见 /session list");
        }
        var sessions = SessionManager.listSessions(workingDir);
        if (sessions.isEmpty()) {
            return Styles.MUTED.render("（还没有历史会话）");
        }
        boolean exists = sessions.stream().anyMatch(s -> s.id().equals(id));
        if (!exists) {
            return Styles.ERROR.render("✗ 找不到会话: " + id);
        }
        return restoreSession(id);
    }

    /**
     * 恢复会话，四步走。
     *
     * <ol>
     *   <li><b>解析</b>：逐行读 JSONL，坏行静默跳过（{@link SessionManager#loadSession}）；</li>
     *   <li><b>修链</b>：补悬空 tool_use、丢孤儿 tool_result（对话层 {@code fixToolCallChain}）；</li>
     *   <li><b>算 token</b>：恢复出来的历史可能早已逼近窗口，超限就先强制压缩一层；</li>
     *   <li><b>时间断层提示</b>：距上次活动超过 24 小时，插一条 system-reminder 说明 Gap。</li>
     * </ol>
     *
     * <p>恢复后 {@code sessionId} 切到目标会话，后续对话继续往同一个 JSONL 追加，
     * 工具结果溢写目录也随之切换，这样回读时还能读到当时的溢写文件。
     */
    private String restoreSession(String id) {
        List<SessionMessage> records = SessionManager.loadSession(workingDir, id);
        if (records.isEmpty()) {
            return Styles.ERROR.render("✗ 会话为空或全部记录损坏: " + id);
        }
        long lastTs = 0;
        for (SessionMessage m : records) {
            lastTs = Math.max(lastTs, m.timestamp());
        }

        var restored = SessionManager.rebuildConversation(records);
        int fixed = restored.fixToolCallChain();

        // 第三步：token 检查
        int before = ContextCompactor.estimateTokens(restored.getMessages());
        int threshold = ContextCompactor.computeCompactThreshold(contextWindow, 0, true);
        boolean compacted = false;
        if (before > threshold && llmClient != null && activeProvider != null) {
            List<Map<String, Object>> schemas =
                    registry.getAllSchemas(activeProvider.normalizedProtocol());
            ContextCompactor.forceCompact(
                    restored, llmClient, contextWindow, workingDir, id, recovery, schemas);
            compacted = true;
        }

        // 第四步：时间断层提示
        long gapMillis = lastTs > 0
                ? System.currentTimeMillis() - lastTs * 1000 : 0L;
        if (gapMillis > 86_400_000L) {
            restored.addSystemReminder(
                    "Note: this session was resumed after a gap of "
                            + (gapMillis / 86_400_000L) + " day(s). "
                            + "Files may have changed since then — verify before relying "
                            + "on earlier statements about the codebase.");
        }

        int count = restored.size();
        conversation.replaceWith(restored);
        this.sessionId = id;
        persistedCount = count;

        int after = ContextCompactor.estimateTokens(conversation.getMessages());
        var lines = new ArrayList<String>();
        lines.add(Styles.ACCENT.render("✻ 已回到会话 " + id + " · 恢复 " + count + " 条消息"));
        if (fixed > 0) {
            lines.add(Styles.MUTED.render("  修复了 " + fixed + " 处不完整的工具调用"));
        }
        if (compacted) {
            lines.add(Styles.MUTED.render("  历史超过阈值已压缩: " + before + " → " + after
                    + " estimated tokens"));
        }
        return String.join("\n", lines);
    }

    // ───────────────────────── 渲染 ─────────────────────────

    private String viewProviderSelect() {
        List<String> lines = new ArrayList<>();
        lines.add(separator());
        lines.add(Styles.ACCENT.render("  请选择要使用的 provider"));
        lines.add("");
        for (int i = 0; i < providers.size(); i++) {
            boolean selected = i == selectedIndex;
            String marker = selected ? Styles.PROMPT.render(" ❯ ") : "   ";
            String label = providers.get(i).getName() + "  (" + providers.get(i).getModel() + ")";
            lines.add(marker + (selected ? Styles.USER.render(label) : Styles.MUTED.render(label)));
        }
        lines.add("");
        lines.add(Styles.MUTED.render("  ↑↓ 移动，Enter 确认，Ctrl+C 退出"));
        lines.add(separator());
        return String.join("\n", lines);
    }

    private String viewChat() {
        List<String> lines = new ArrayList<>();
        lines.add(separator());

        if (streaming) {
            long elapsed = System.currentTimeMillis() - requestStartMillis;
            lines.add(Styles.SPINNER.render("✻ " + SpinnerVerbs.frame() + " " + spinnerVerb + "… ("
                    + (elapsed / 1000) + "s)"));
            if (!streamBuf.isEmpty()) {
                lines.addAll(List.of(streamBuf.toString().split("\n", -1)));
            }
            lines.add(separator());
        }

        lines.addAll(renderInputBox());
        lines.add(renderStatusBar());
        return String.join("\n", lines);
    }

    /** 底部带边框的输入框，含 ❯ 提示符与占位文字（F7-d）。 */
    private List<String> renderInputBox() {
        int boxWidth = Math.max(20, width);
        int innerWidth = boxWidth - 4;

        List<String> rows = new ArrayList<>();
        String raw = input.toString();
        if (raw.isEmpty()) {
            String placeholder = planMode ? "Plan mode: 探索代码并给出计划…" : "Send a message...";
            rows.add(Styles.PROMPT.render("❯ ") + Styles.PLACEHOLDER.render(placeholder));
        } else {
            String[] logicalLines = raw.split("\n", -1);
            for (int i = 0; i < logicalLines.length; i++) {
                List<String> wrapped = wrapPlain(logicalLines[i], innerWidth - 2);
                for (int j = 0; j < wrapped.size(); j++) {
                    String prefix = (i == 0 && j == 0) ? Styles.PROMPT.render("❯ ") : "  ";
                    rows.add(prefix + wrapped.get(j));
                }
            }
        }

        List<String> out = new ArrayList<>();
        out.add(Styles.BORDER.render("╭" + "─".repeat(boxWidth - 2) + "╮"));
        for (String row : rows) {
            String padded = row + " ".repeat(Math.max(0, innerWidth - Ansi.displayWidth(row)));
            out.add(Styles.BORDER.render("│") + " " + padded + " " + Styles.BORDER.render("│"));
        }
        out.add(Styles.BORDER.render("╰" + "─".repeat(boxWidth - 2) + "╯"));
        return out;
    }

    /**
     * 状态栏：模式 + token 用量 + 一条随模式变化的命令提示。
     *
     * <pre>
     * AgentJava [DEFAULT]  tokens: 45,230/200k · deepseek-chat · /help 查看命令
     * AgentJava [PLAN]     tokens: 12,300/200k · deepseek-chat · /plan 退出 Plan 模式
     * </pre>
     *
     * <p>提示语跟着模式走是有意为之：用户最容易困惑的不是「有什么命令」，
     * 而是「我现在处在什么状态、怎么出去」。宽度不够时依次丢弃模型名、token 用量，
     * 保证模式标记与提示始终可见。
     */
    private String renderStatusBar() {
        String mode = planMode ? "PLAN" : switch (permissionMode) {
            case DEFAULT -> "DEFAULT";
            case ACCEPT_EDITS -> "ACCEPT";
            case BYPASS -> "BYPASS";
            case PLAN -> "PLAN";
        };
        String left = (activeProvider == null ? "-" : activeProvider.getName())
                + Styles.ACCENT.render(" [" + mode + "]");
        String tokens = "tokens: " + thousands(getTokenCount()) + "/" + shortNumber(contextWindow);
        String model = activeProvider == null ? "-" : activeProvider.getModel();
        String hint = planMode ? "/plan 退出 Plan 模式" : "/help 查看命令";
        int visible = width > 0 ? width : 80;

        // 从左到右依次丢弃：token 用量先让位，最后是模式标记与提示语
        List<String> parts = new ArrayList<>();
        parts.add(tokens);
        if (usageAvailable) {
            parts.add("↑" + lastInputTokens + " ↓" + lastOutputTokens);
        }
        parts.add(model);
        parts.add(hint);
        while (parts.size() > 1
                && Ansi.displayWidth(left) + 1 + Ansi.displayWidth(String.join(" · ", parts)) > visible) {
            parts.remove(0);
        }
        String right = String.join(" · ", parts);
        int gap = Math.max(1, visible - Ansi.displayWidth(left) - Ansi.displayWidth(right));
        return left + " ".repeat(gap) + right;
    }

    private static String thousands(int n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** 按第一个空白拆成「子命令 + 剩余参数」两段，没输入时返回空串而不是 null。 */
    private static String[] splitOnce(String args) {
        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isEmpty()) {
            return new String[]{"", ""};
        }
        String[] parts = trimmed.split("\\s+", 2);
        return parts.length == 1 ? new String[]{parts[0], ""} : parts;
    }

    /** token 总量的紧凑写法：200000 → 200k。 */
    private static String shortNumber(int n) {
        return n >= 1_000 ? (n / 1_000) + "k" : String.valueOf(n);
    }

    private String renderBanner() {
        String[] cat = {
                "   /\\_/\\",
                "  ( o.o )",
                "   > ^ <",
        };
        String[] info = {
                Styles.USER.render(APP_NAME + " v" + VERSION),
                Styles.MUTED.render(workingDir),
                "",
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cat.length; i++) {
            sb.append(cat[i]);
            if (i < info.length && !info[i].isEmpty()) {
                sb.append("   ").append(info[i]);
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** 启动后的一行就绪提示：只指个方向，完整的命令清单在 /help 里。 */
    private String renderReadyLine() {
        return Styles.ACCENT.render("✻ 已就绪")
                + Styles.MUTED.render(" · " + EXIT_COMMAND + " 退出 · Tab 补全命令")
                + Styles.MUTED.render("\n  /help 看全部命令 · /status 看当前状态 · /plan 计划模式 · /review 审查变更");
    }

    private String separator() {
        return Styles.BORDER.render("─".repeat(Math.max(10, width)));
    }

    // ───────────────────────── 小工具 ─────────────────────────

    /** 按可见宽度软换行。 */
    private static List<String> wrapPlain(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (maxWidth <= 1) {
            out.add(text);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        int w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int cw = cp > 0x2E80 ? 2 : 1;
            if (w + cw > maxWidth) {
                out.add(cur.toString());
                cur.setLength(0);
                w = 0;
            }
            cur.appendCodePoint(cp);
            w += cw;
            i += Character.charCount(cp);
        }
        out.add(cur.toString());
        return out;
    }

    private static String formatSeconds(long millis) {
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    /** 工具调用参数的紧凑预览：直接打印 Map，过长截断。 */
    private static String previewArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        String s = args.toString();
        if (s.length() > 100) {
            s = s.substring(0, 100) + "…";
        }
        return s;
    }

    /** 工具结果的紧凑预览：取首行，过长截断。 */
    private static String previewResult(String content) {
        if (content == null || content.isBlank()) {
            return "(空)";
        }
        String firstLine = content.split("\n", -1)[0];
        if (firstLine.length() > 120) {
            firstLine = firstLine.substring(0, 120) + "…";
        }
        int lines = (int) content.lines().count();
        return lines > 1 ? firstLine + " (+" + (lines - 1) + " 行)" : firstLine;
    }
}
