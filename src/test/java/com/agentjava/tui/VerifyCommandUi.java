package com.agentjava.tui;

import com.agentjava.command.CommandParser;
import com.agentjava.config.ProviderConfig;
import com.agentjava.tui.tea.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI 集成侧验证：命令拦截、Y 型分流、三种类型的返回值处理。
 *
 * <p>{@link AgentModel} 这一层只做「把命令的返回值翻译成 TEA 副作用指令」，
 * 所以断言的对象是返回出来的 {@link Command}，而不是终端输出本身。
 */
class VerifyCommandUi {

    @TempDir
    Path tempDir;

    private String originalHome;
    private AgentModel model;

    @BeforeEach
    void setUp() {
        // 把家目录挪到临时目录：MemoryManager 构造时会确保 ~/.agentjava/memory 存在
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        ProviderConfig cfg = new ProviderConfig(
                "test", "openai-compat", "http://127.0.0.1:1/v1", "test-key", "test-model", false);
        model = new AgentModel(List.of(cfg), tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    private String dispatch(String line) {
        Command result = model.dispatchSlashCommand(CommandParser.parse(line));
        Command.PrintLine printed = assertInstanceOf(Command.PrintLine.class, result);
        return printed.text();
    }

    @Test
    @DisplayName("未知命令的报错必须带上 /help 这个出口")
    void unknownCommandPointsToHelp() {
        String out = dispatch("/compresssss");
        assertTrue(out.contains("未知命令：/compresssss"), out);
        assertTrue(out.contains("/help 查看可用命令"), out);
    }

    @Test
    @DisplayName("只敲一个斜杠就直接给命令清单")
    void bareSlashListsCommands() {
        String out = dispatch("/");
        assertTrue(out.contains("可用命令："), out);
        assertTrue(out.contains("/session"), out);
        assertTrue(out.contains("/review"), out);
        // 隐藏命令不出现在清单里，但确实存在于注册中心
        assertTrue(out.contains("/status"), out);
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("/exit"), out);
    }

    @Test
    @DisplayName("需要参数的命令缺参数时给的是 argPrompt，不是「缺少参数」")
    void missingArgsShowsArgPrompt() {
        String out = dispatch("/resume");
        assertTrue(out.contains("用法: /resume <会话 ID>"), out);
        assertTrue(out.contains("/session list"), out);
    }

    @Test
    @DisplayName("/exit 翻译成退出指令，而不是一段文本")
    void exitMapsToQuitCommand() {
        assertInstanceOf(Command.Quit.class,
                model.dispatchSlashCommand(CommandParser.parse("/exit")));
        // 别名同样有效
        assertInstanceOf(Command.Quit.class,
                model.dispatchSlashCommand(CommandParser.parse("/quit")));
    }

    @Test
    @DisplayName("/plan 与 /do 真的改变了 UI 行为状态（状态栏跟着变）")
    void planModeTogglesUiState() {
        String entered = dispatch("/plan");
        assertTrue(entered.contains("已进入 Plan Mode"), entered);
        assertTrue(model.view().contains("[PLAN]"), model.view());
        assertTrue(model.view().contains("/plan 退出 Plan 模式"), model.view());

        String exited = dispatch("/do");
        assertTrue(exited.contains("已退出 Plan Mode"), exited);
        assertTrue(model.view().contains("[DEFAULT]"), model.view());
    }

    @Test
    @DisplayName("/plan 带参数：切模式的同时把任务描述投递给 Agent")
    void planWithTaskSendsDescriptionToAgent() {
        // llmClient 已激活（不发包），这里会有「echo + 起一轮 Loop」两条指令
        Command result = model.dispatchSlashCommand(CommandParser.parse("/plan 设计用户认证模块"));
        Command.Batch batch = assertInstanceOf(Command.Batch.class, result);
        assertEquals(2, batch.commands().size());
        String echo = ((Command.PrintLine) batch.commands().get(1)).text();
        assertTrue(echo.contains("设计用户认证模块"), echo);
        assertTrue(model.view().contains("[PLAN]"), model.view());
    }

    @Test
    @DisplayName("prompt 类型命令的返回值被当成用户输入，不是当垃圾文本打出来")
    void reviewResultGoesToAgent() {
        Command result = model.dispatchSlashCommand(CommandParser.parse("/review 特别注意并发安全"));
        Command.Batch batch = assertInstanceOf(Command.Batch.class, result);
        // 第一条是 /review 挂过来的系统消息，第二条是发给 Agent 的 echo
        String notice = ((Command.PrintLine) batch.commands().get(0)).text();
        assertTrue(notice.contains("消耗 token"), notice);
        String echo = ((Command.PrintLine) batch.commands().get(1)).text();
        assertTrue(echo.contains("请审查当前 git diff 中的代码变更"), echo);
        assertTrue(echo.contains("额外关注：特别注意并发安全"), echo);
    }

    @Test
    @DisplayName("/status 与 /help 直接落成文本输出（local 类型）")
    void localCommandsPrintText() {
        String status = dispatch("/status");
        assertTrue(status.contains("AgentJava 状态"), status);
        assertTrue(status.contains("版本：v" + AgentModel.VERSION), status);

        String detail = dispatch("/help status");
        assertTrue(detail.contains("/status, /s — 显示当前状态"), detail);
    }

    @Test
    @DisplayName("/clear 换会话：新会话 ID 与旧的不同，且记忆装配不会丢")
    void clearStartsFreshSession() {
        String before = dispatch("/status");
        String out = dispatch("/clear");
        assertTrue(out.contains("已开启新会话"), out);
        String after = dispatch("/status");
        org.junit.jupiter.api.Assertions.assertNotEquals(before, after);
        assertTrue(model.view().contains("[DEFAULT]"), model.view());
    }

    @Test
    @DisplayName("上下文很小的时候 /compact 直接说无需压缩，不白跑一次 LLM")
    void compactRefusesSmallContext() {
        String out = dispatch("/compact");
        assertTrue(out.contains("无需压缩"), out);
        assertTrue(out.contains("阈值 5000"), out);
    }
}
