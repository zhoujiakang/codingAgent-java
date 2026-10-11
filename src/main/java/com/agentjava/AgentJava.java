package com.agentjava;

import com.agentjava.config.AppConfig;
import com.agentjava.config.ConfigException;
import com.agentjava.config.ConfigLoader;
import com.agentjava.config.ProviderConfig;
import com.agentjava.mcp.McpManager;
import com.agentjava.mcp.McpServerConfig;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.conversation.ToolResultBlock;
import com.agentjava.conversation.ToolUseBlock;
import com.agentjava.llm.LlmClient;
import com.agentjava.llm.LlmException;
import com.agentjava.llm.StreamEvent;
import com.agentjava.prompt.PromptBuilder;
import com.agentjava.teams.TeamManager;
import com.agentjava.teams.TeamTaskTools;
import com.agentjava.teams.TeamTools;
import com.agentjava.teams.TeammateRunner;
import com.agentjava.tool.SyntheticOutputTool;
import com.agentjava.tool.ToolExecutor;
import com.agentjava.tool.ToolRegistry;
import com.agentjava.tui.AgentModel;
import com.agentjava.tui.MarkdownRenderer;
import com.agentjava.tui.tea.Program;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * 程序入口：装配与启动。
 *
 * 流程：加载配置 → 构造 TUI 模型 → 交给 Program 跑事件循环。
 * 配置出问题时打印一句可读提示后退出，而不是甩一屏堆栈（N4）。
 *
 * 除了默认的全屏交互模式，还支持 -p "问题" 的非交互模式：
 * 发一轮请求、把回复流式打到标准输出就结束。方便在没有 TTY 的环境里验证链路。
 */
public class AgentJava {

    public static void main(String[] args) {
        // 队友进程入口：被 tmux/iTerm 拉起时命令以 --teammate 打头，走队友模式而非 TUI。
        // 格式：agentjava --teammate --team-name <t> --agent-name <n>（见 SpawnDispatcher.buildTeammateCLI）
        if (args.length > 0 && "--teammate".equals(args[0])) {
            runTeammate(args);
            return;
        }
        if (args.length > 0 && ("-p".equals(args[0]) || "--prompt".equals(args[0]))) {
            if (args.length < 2) {
                System.err.println("用法：agentjava -p \"你的问题\"");
                System.exit(2);
            }
            runOneShot(args[1]);
            return;
        }
        runInteractive();
    }

    /** 交互模式：启动 TUI。 */
    private static void runInteractive() {
        AppConfig config = loadConfig();
        List<McpServerConfig> mcpConfigs = ConfigLoader.loadMcpServers();
        AgentModel model;
        try {
            model = new AgentModel(config.getProviders(), System.getProperty("user.dir"),
                    mcpConfigs, config.isEnableCoordinatorMode());
        } catch (LlmException e) {
            System.err.println("初始化失败：" + e.getMessage());
            System.exit(1);
            return;
        }
        try {
            Program program = new Program(model);
            // 模型要靠 Program 把流式事件投回事件循环，所以建好之后回灌一次
            model.setProgram(program);
            program.run();
        } catch (IOException e) {
            System.err.println("无法启动终端界面：" + e.getMessage());
            System.exit(1);
        } finally {
            // shutdown + session_end：正常退出时补一次收尾事件
            model.fireShutdownHooks();
        }
    }

    /** 非交互模式：发一轮就走，用于脚本和验证。支持模型发起工具调用（单步循环）。 */
    private static void runOneShot(String prompt) {
        AppConfig config = loadConfig();
        List<ProviderConfig> providers = config.getProviders();
        ProviderConfig cfg = providers.get(0);
        try {
            LlmClient client = LlmClient.create(cfg,
                    PromptBuilder.buildSystemPrompt(PromptBuilder.detectEnvironment(cfg.getModel()), null));
            ToolRegistry registry = ToolRegistry.createDefault();
            // 非交互模式同样接入 MCP（若有配置）
            List<McpServerConfig> mcpConfigs = ConfigLoader.loadMcpServers();
            if (!mcpConfigs.isEmpty()) {
                var mcpManager = new McpManager(mcpConfigs);
                var mcpResult = mcpManager.registerAllTools(registry);
                if (!mcpResult.errors().isEmpty()) {
                    System.err.println("⚠ MCP 接入异常: " + String.join("; ", mcpResult.errors()));
                } else if (mcpResult.serverCount() > 0) {
                    System.out.println("✻ 已连接 " + mcpResult.serverCount() + " 个 MCP Server，注册 "
                            + mcpResult.tools().size() + " 个工具");
                }
                Runtime.getRuntime().addShutdownHook(new Thread(mcpManager::shutdown));
            }
            List<Map<String, Object>> tools = registry.getAllSchemas(cfg.normalizedProtocol());
            ConversationManager conversation = new ConversationManager();
            conversation.addUserMessage(prompt);

            StringBuilder full = new StringBuilder();
            long start = System.currentTimeMillis();
            int rounds = 0;
            while (rounds < 6) {
                rounds++;
                full.setLength(0);
                BlockingQueue<StreamEvent> queue = client.stream(conversation, tools);
                List<ToolUseBlock> pending = new ArrayList<>();
                while (true) {
                    StreamEvent event = queue.take();
                    if (event instanceof StreamEvent.TextDelta delta) {
                        full.append(delta.text());
                        System.out.print(delta.text());
                        System.out.flush();
                    } else if (event instanceof StreamEvent.ToolCallStart s) {
                        System.out.println();
                        System.out.println("⏺ " + s.toolName());
                    } else if (event instanceof StreamEvent.ToolCallDelta ignored) {
                        // 参数碎片不打到终端，避免刷屏
                    } else if (event instanceof StreamEvent.ToolCallComplete c) {
                        pending.add(new ToolUseBlock(c.toolId(), c.toolName(), c.arguments()));
                    } else if (event instanceof StreamEvent.Error error) {
                        System.out.println();
                        System.err.println("✗ " + error.message());
                        System.exit(1);
                        return;
                    } else if (event instanceof StreamEvent.StreamEnd end) {
                        if (!pending.isEmpty()) {
                            conversation.addAssistantMessageWithTools(full.toString(), new ArrayList<>(pending));
                            var results = ToolExecutor.execute(registry, pending);
                            conversation.addToolResultsMessage(results);
                            for (int i = 0; i < pending.size(); i++) {
                                ToolResultBlock r = results.get(i);
                                String firstLine = r.content().split("\n", -1)[0];
                                if (firstLine.length() > 120) {
                                    firstLine = firstLine.substring(0, 120) + "…";
                                }
                                System.out.println("  ↳ " + firstLine);
                            }
                            break; // 跳出内层循环，进入下一轮（把工具结果喂回模型）
                        }
                        System.out.println();
                        System.out.println();
                        System.out.println(MarkdownRenderer.render(full.toString(), 100));
                        System.out.println("↑" + end.inputTokens() + " ↓" + end.outputTokens()
                                + " · " + (System.currentTimeMillis() - start) / 1000.0 + "s");
                        return;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (LlmException e) {
            System.err.println("初始化失败：" + e.getMessage());
            System.exit(1);
        }
    }

    private static AppConfig loadConfig() {
        try {
            return ConfigLoader.load(ConfigLoader.resolveConfigPath());
        } catch (ConfigException e) {
            System.err.println("配置错误：" + e.getMessage());
            System.exit(1);
            throw new IllegalStateException("unreachable");
        }
    }

    /**
     * 队友工作进程入口：加载配置、建 LLM 客户端和工具白名单，接入 lead 建好的团队，
     * 然后进入 {@link TeammateRunner} 的常驻循环。
     *
     * <p>与 TUI 的区别只有两点：没有界面，工具集里也没有 Agent（队友不再往下派子 Agent）；
     * 通信完全依赖团队目录下的文件邮箱，所以它跟 Lead 进程不共享任何内存。
     */
    private static void runTeammate(String[] args) {
        // 解析队友专用参数：只认 --team-name / --agent-name，其余忽略
        String teamName = null;
        String memberName = null;
        for (int i = 1; i < args.length; i++) {
            if ("--team-name".equals(args[i]) && i + 1 < args.length) {
                teamName = args[++i];
            } else if ("--agent-name".equals(args[i]) && i + 1 < args.length) {
                memberName = args[++i];
            }
        }
        if (teamName == null || teamName.isEmpty() || memberName == null || memberName.isEmpty()) {
            System.err.println("--teammate requires --team-name and --agent-name");
            System.exit(1);
            return;
        }

        // 加载与 TUI 相同的配置
        AppConfig config;
        try {
            config = ConfigLoader.load(ConfigLoader.resolveConfigPath());
        } catch (ConfigException e) {
            System.err.println("配置错误：" + e.getMessage());
            System.exit(1);
            return;
        }
        if (config.getProviders() == null || config.getProviders().isEmpty()) {
            System.err.println("没有可用的 provider 配置");
            System.exit(1);
            return;
        }
        ProviderConfig provider = config.getProviders().get(0);

        String workDir = System.getProperty("user.dir");
        String sessionId = java.util.UUID.randomUUID().toString();

        // Skill 目录：既要让系统提示词知道有哪些技能，也要挂到 LoadSkill 工具上
        var skillCatalog = com.agentjava.skill.SkillCatalog.loadCatalog(workDir);

        // 系统提示词与 LLM 客户端
        String systemPrompt = PromptBuilder.buildSystemPrompt(
                PromptBuilder.detectEnvironment(provider.getModel()), null);
        LlmClient client = LlmClient.create(provider, systemPrompt);
        String protocol = provider.normalizedProtocol();

        // 队员进程直接从磁盘上的 config.json 把团队捞回来，这样它看到的队友名单
        // 和 Lead 那边是同一份。团队目录在用户主目录下，不受 worktree 换工作目录影响。
        TeamManager teamMgr = new TeamManager();
        TeamManager.Team team = teamMgr.getTeam(teamName);
        if (team == null) {
            // 配置还没落盘（例如 Lead 刚建完团队就 spawn），退化成本地构造一份，
            // 邮箱目录按同样的约定拼出来，投递仍然对得上。
            team = new TeamManager.Team(teamName, TeamManager.TeamMode.IN_PROCESS);
            teamMgr.createTeamWith(team);
        }

        ToolRegistry registry = buildTeammateRegistry(workDir, sessionId, teamMgr, teamName, memberName);

        TeamManager.Member member = team.addMember(memberName, client, registry, protocol, provider);

        // 激活的 SOP 由 LoadSkill 的工具结果带回对话，不必另外注入一份。
        // 没有 ForkHost，声明 fork 模式的 skill 会退回 inline 执行
        var loadSkillTool = new com.agentjava.tool.impl.LoadSkillTool();
        loadSkillTool.setCatalog(skillCatalog);
        registry.register(loadSkillTool);

        // 队友的 skill 清单作为 system reminder 注入对话（与主 Agent 的注入点一致）
        String skillSection = skillCatalog.buildSection(workDir);
        if (!skillSection.isBlank()) {
            member.conv.addSystemReminder(skillSection);
        }

        // 关闭窗格 / Ctrl-C 时中断队友循环，让对话持久化等收尾逻辑得以执行
        Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(mainThread::interrupt));

        String addendum = TeammateRunner.buildTeammateAddendum(teamName, memberName, null);

        // 不传初始 prompt：lead 在 spawn 前已把首个任务写入邮箱，循环首轮空闲轮询即可取到，
        // 传空可避免 runInProcessTeammate 注入重复的用户消息
        System.err.printf("[teammate %s/%s] booted, awaiting tasks%n", teamName, memberName);
        TeammateRunner.runInProcessTeammate(team, member, "", addendum);
    }

    /**
     * 组装队友工具集：文件与命令工具，加上团队协作工具（按自己的名字发消息、
     * 读写团队共享任务板）。任务板按团队名解析到同一份 tasks.json，所以队友之间
     * 看到的是同一张表。
     *
     * <p>Agent 不在其中：调用树到队友这一层为止，队友不再往下派子 Agent。
     * TeamCreate 与 TeamDelete 也不在其中：组建和解散团队是 Lead 的职责。
     */
    private static ToolRegistry buildTeammateRegistry(
            String workDir,
            String sessionId,
            TeamManager teamMgr,
            String teamName,
            String memberName) {
        ToolRegistry registry = ToolRegistry.createDefault();
        registry.register(new SyntheticOutputTool());

        // SendMessage 以「自己的名字」为发件人；任务板按团队名解析到同一份 tasks.json
        registry.register(new TeamTools.SendMessageTool(teamMgr, memberName));
        registry.register(new TeamTaskTools.TaskCreateTool(teamMgr, teamName, memberName));
        registry.register(new TeamTaskTools.TaskGetTool(teamMgr, teamName));
        registry.register(new TeamTaskTools.TaskListTool(teamMgr, teamName));
        registry.register(new TeamTaskTools.TaskUpdateTool(teamMgr, teamName));
        return registry;
    }
}
