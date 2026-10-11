package com.agentjava.tui;

import com.agentjava.command.CommandParser;
import com.agentjava.config.ProviderConfig;
import com.agentjava.tui.tea.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI 集成侧验证：子 Agent 在启动时装配成工具，{@code /tasks} 与
 * {@code /task} 能看见它。
 *
 * <p>这一层验证的是「接线」：定义文件落到磁盘上之后，AgentModel 构造时会不会真的去读、
 * 读出来会不会注册成 Agent 工具、坏文件会不会给出可读的定位、任务命令能不能查到状态。
 */
class VerifySubAgentUi {

    @TempDir
    Path tempDir;

    private String originalHome;
    private Path project;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        project = tempDir.resolve("project");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    @DisplayName("/tasks：内置三件套 + 项目/用户级定义 + 优先级说明")
    void tasksListsAgentTypes() throws IOException {
        writeAgent(project.resolve(".agentjava/agents/reviewer.md"), """
                ---
                name: reviewer
                description: 项目级安全审查员
                ---
                审查正文
                """);

        String out = dispatch(newModel(), "/tasks");

        assertTrue(out.contains("还没有启动过子 Agent"), out);
        assertTrue(out.contains("可用 subagent_type"), out);
        assertTrue(out.contains("项目级 > 用户级 > 内置级 > 插件级"), out);
        assertTrue(out.contains("reviewer"), out);
        assertTrue(out.contains("项目级安全审查员"), out);
        // 内置的三个也都在（列表顺序：插件 → 内置 → 用户 → 项目）
        assertTrue(out.contains("general-purpose"), out);
        assertTrue(out.contains("plan"), out);
        assertTrue(out.contains("explore"), out);
        assertTrue(out.contains("留空则走 Fork 路径"), out);
    }

    @Test
    @DisplayName("坏定义文件：启动不崩，/tasks 里给出定位到文件的错误")
    void badAgentFileIsReportedNotFatal() throws IOException {
        writeAgent(project.resolve(".agentjava/agents/broken.md"), """
                ---
                name: broken
                ---
                少了 description
                """);
        writeAgent(project.resolve(".agentjava/agents/good.md"), """
                ---
                name: good
                description: 正常定义
                ---
                正文
                """);

        AgentModel model = newModel();
        String out = dispatch(model, "/tasks");

        assertTrue(out.contains("good"), "好定义要留下来：" + out);
        // 坏掉的那个不能出现在「可用 subagent_type」清单里（正文里的报错路径另说）
        assertFalse(model.registry().get("Agent").description().contains("broken"),
                "坏定义不该进可用列表");
        assertTrue(out.contains("定义文件问题（1）"), out);
        assertTrue(out.contains("broken.md"), out);
        assertTrue(out.contains("description"), out);
    }

    @Test
    @DisplayName("/task info 与 /task cancel：没任务时给用法，未知 ID 给明确提示")
    void taskSubcommandsGuideUser() {
        AgentModel model = newModel();

        assertTrue(dispatch(model, "/task info").contains("用法：/task info"));
        assertTrue(dispatch(model, "/task cancel").contains("用法：/task cancel"));
        assertTrue(dispatch(model, "/task info task_99").contains("找不到任务: task_99"));
        assertTrue(dispatch(model, "/task cancel task_99").contains("取消失败: task_99"));
        assertTrue(dispatch(model, "/task nope x").contains("未知子命令：nope"));
    }

    @Test
    @DisplayName("Agent 工具注册进工具注册表：模型能用它派发子任务")
    void agentToolIsRegistered() {
        AgentModel model = newModel();

        var tool = model.registry().get("Agent");

        assertTrue(tool != null, "Agent 工具必须注册上，否则模型根本没有派发入口");
        // 描述里带上可用类型，这是模型判断「派谁」的唯一信息来源
        assertTrue(tool.description().contains("general-purpose"), tool.description());
        assertTrue(tool.description().contains("Fork"), tool.description());
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private AgentModel newModel() {
        ProviderConfig cfg = new ProviderConfig(
                "test", "openai-compat", "http://127.0.0.1:1/v1", "test-key", "test-model", false);
        // 构造即加载 Agent 定义，所以定义文件必须在 new AgentModel 之前写进磁盘
        return new AgentModel(List.of(cfg), project.toString());
    }

    private static void writeAgent(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** 把命令的副作用指令摊平成一段文本。 */
    private static String dispatch(AgentModel model, String line) {
        StringBuilder sb = new StringBuilder();
        flatten(model.dispatchSlashCommand(CommandParser.parse(line)), sb);
        return sb.toString();
    }

    private static void flatten(Command cmd, StringBuilder out) {
        if (cmd instanceof Command.PrintLine p) {
            out.append(p.text()).append('\n');
        } else if (cmd instanceof Command.Batch b) {
            for (Command c : b.commands()) {
                flatten(c, out);
            }
        }
    }
}
