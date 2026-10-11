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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI 集成侧验证：Hook 在启动时自动加载，{@code /hooks} 能看到它们。
 *
 * <p>这一层验证的是「接线」：配置文件落到磁盘上之后，AgentModel 构造时会不会真的去读、
 * 读出来会不会登记成命令、坏配置会不会给出可读的定位。
 */
class VerifyHooksUi {

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
    @DisplayName("启动即加载项目级 Hook，/hooks 能看到事件、条件、动作与来源")
    void hooksAreLoadedAndListed() throws IOException {
        writeProjectConfig("""
                hooks:
                  - id: block-json-write
                    event: pre_tool_use
                    if: 'tool == "WriteFile" && args.file_path =~ /\\.json$/'
                    action:
                      type: command
                      command: 'echo "禁止直接写入 JSON 文件，请使用专用工具"'
                    reject: true
                  - id: project-context
                    event: session_start
                    action:
                      type: prompt
                      message: 项目技术栈：Java 21 + Gradle
                    once: true
                """);
        AgentModel model = newModel();

        String out = dispatch(model, "/hooks");
        assertTrue(out.contains("已加载 Hook（2 条）"), out);
        assertTrue(out.contains("block-json-write"), out);
        assertTrue(out.contains("project-context"), out);
        assertTrue(out.contains("pre_tool_use"), out);
        assertTrue(out.contains("session_start"), out);
        assertTrue(out.contains("项目级"), out);
        assertTrue(out.contains("reject"), out);
        assertTrue(out.contains("once"), out);
        assertTrue(out.contains("禁止直接写入 JSON 文件"), out);
    }

    @Test
    @DisplayName("坏配置不让启动崩，/hooks 里给出定位到具体条目的错误")
    void badConfigIsReportedNotFatal() throws IOException {
        writeProjectConfig("""
                hooks:
                  - id: good
                    event: post_tool_use
                    action:
                      type: prompt
                      message: ok
                  - id: bad
                    event: session_start
                    action:
                      type: prompt
                      message: nope
                    reject: true
                """);
        AgentModel model = newModel();

        String out = dispatch(model, "/hooks");
        assertTrue(out.contains("已加载 Hook（1 条）"), "好规则要留下来：" + out);
        assertTrue(out.contains("配置问题（1）"), out);
        assertTrue(out.contains("bad"), out);
        assertTrue(out.contains("reject 只能用在 pre_tool_use"), out);
    }

    @Test
    @DisplayName("没配 Hook 时给指明三处配置文件的引导，而不是一句冷冰冰的空")
    void emptyHooksShowsGuidance() {
        AgentModel model = newModel();

        String out = dispatch(model, "/hooks");
        assertTrue(out.contains("还没有配置任何 Hook"), out);
        assertTrue(out.contains("项目级"), out);
        assertTrue(out.contains("用户级"), out);
        assertTrue(out.contains("本地级"), out);
        assertTrue(out.contains("追加合并"), "要跟 Skill 的「找到一个就停」区分开：" + out);
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private AgentModel newModel() {
        ProviderConfig cfg = new ProviderConfig(
                "test", "openai-compat", "http://127.0.0.1:1/v1", "test-key", "test-model", false);
        // 构造即加载 Hook 配置，所以配置必须在 new AgentModel 之前写进磁盘
        return new AgentModel(List.of(cfg), project.toString());
    }

    private void writeProjectConfig(String yaml) throws IOException {
        Path file = project.resolve(".agentjava/config.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
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
