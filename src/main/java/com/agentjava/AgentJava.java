package com.agentjava;

import com.agentjava.config.AppConfig;
import com.agentjava.config.ConfigException;
import com.agentjava.config.ConfigLoader;
import com.agentjava.config.ProviderConfig;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.llm.LlmClient;
import com.agentjava.llm.LlmException;
import com.agentjava.llm.StreamEvent;
import com.agentjava.prompt.PromptBuilder;
import com.agentjava.tui.AgentModel;
import com.agentjava.tui.MarkdownRenderer;
import com.agentjava.tui.tea.Program;

import java.io.IOException;
import java.util.List;
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
        AgentModel model;
        try {
            model = new AgentModel(config.getProviders(), System.getProperty("user.dir"));
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
        }
    }

    /** 非交互模式：发一轮就走，用于脚本和验证。 */
    private static void runOneShot(String prompt) {
        AppConfig config = loadConfig();
        List<ProviderConfig> providers = config.getProviders();
        ProviderConfig cfg = providers.get(0);
        try {
            LlmClient client = LlmClient.create(cfg, PromptBuilder.buildSystemPrompt(System.getProperty("user.dir")));
            ConversationManager conversation = new ConversationManager();
            conversation.addUserMessage(prompt);

            StringBuilder full = new StringBuilder();
            long start = System.currentTimeMillis();
            BlockingQueue<StreamEvent> queue = client.stream(conversation);
            while (true) {
                StreamEvent event = queue.take();
                if (event instanceof StreamEvent.TextDelta delta) {
                    full.append(delta.text());
                    System.out.print(delta.text());
                    System.out.flush();
                } else if (event instanceof StreamEvent.Error error) {
                    System.out.println();
                    System.err.println("✗ " + error.message());
                    System.exit(1);
                    return;
                } else if (event instanceof StreamEvent.StreamEnd end) {
                    System.out.println();
                    System.out.println();
                    System.out.println(MarkdownRenderer.render(full.toString(), 100));
                    System.out.println("↑" + end.inputTokens() + " ↓" + end.outputTokens()
                            + " · " + (System.currentTimeMillis() - start) / 1000.0 + "s");
                    return;
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
}
