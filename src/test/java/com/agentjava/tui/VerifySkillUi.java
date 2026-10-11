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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI 集成侧验证：Skill 自动注册成斜杠命令、内置命令优先级、{@code /skill} 管理。
 *
 * <p>这一层验证的是「用户敲下去发生了什么」——Skill 系统最终要落到一条斜杠命令上，
 * 单元测试覆盖不到的正是这段接线。
 */
class VerifySkillUi {

    @TempDir
    Path tempDir;

    private String originalHome;
    private AgentModel model;
    private Path project;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        project = tempDir.resolve("project");
        ProviderConfig cfg = new ProviderConfig(
                "test", "openai-compat", "http://127.0.0.1:1/v1", "test-key", "test-model", false);
        // 构造即扫描 Skill 目录，所以 Skill 必须在 new AgentModel 之前写进磁盘
        model = new AgentModel(List.of(cfg), project.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    /**
     * 把命令的副作用指令摊平成一段文本。
     * prompt 类型命令会同时挂一条系统消息和一条 echo，所以结果可能是 Batch。
     */
    private String dispatch(String line) {
        Command result = model.dispatchSlashCommand(CommandParser.parse(line));
        StringBuilder sb = new StringBuilder();
        flatten(result, sb);
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

    // ───────────────────────── 自动注册 ─────────────────────────

    @Test
    @DisplayName("项目级 Skill 自动变成斜杠命令，敲下去就把 SOP 交给 Agent")
    void skillBecomesSlashCommand() throws IOException {
        writeSkill("commit", "commit", "生成规范的 commit",
                "## Commit SOP\n\n1. git status\n2. git diff --stat");
        // Skill 是启动期扫描的，写完之后要显式重载一次
        reload();

        String out = dispatch("/commit");
        assertTrue(out.contains("## Commit SOP"), out);
        assertTrue(out.contains("git diff --stat"), out);
    }

    @Test
    @DisplayName("命令参数按 $ARGUMENTS 渲染进 SOP，不是被丢掉")
    void skillArgumentsAreSubstituted() throws IOException {
        writeSkill("test", "test", "跑测试",
                "## Test SOP\n\n只对 $ARGUMENTS 跑测试");
        reload();

        String out = dispatch("/test service 包");
        assertTrue(out.contains("只对 service 包 跑测试"), out);
    }

    @Test
    @DisplayName("Skill 出现在 /help 与 Tab 补全里，跟内置命令一样的待遇")
    void skillShowsUpInHelpAndCompletion() throws IOException {
        writeSkill("changelog", "changelog", "生成变更日志", "## 变更日志 SOP");
        reload();

        String help = dispatch("/help");
        assertTrue(help.contains("/changelog"), help);
        assertTrue(model.commandCandidates("/chan").contains("/changelog"),
                model.commandCandidates("/chan").toString());
    }

    @Test
    @DisplayName("内置命令优先：Skill 不能顶掉 /review 这类保底能力")
    void builtinCommandWinsOverSameNamedSkill() throws IOException {
        writeSkill("review", "review", "第三方写的审查", "## 第三方审查 SOP");
        reload();

        String out = dispatch("/review");
        assertTrue(out.contains("请审查当前 git diff 中的代码变更"), out);
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("第三方审查 SOP"), out);
        // 但 Skill 本身仍然在目录里，能被 LoadSkill 加载
        String info = dispatch("/skill info review");
        assertTrue(info.contains("第三方写的审查"), info);
    }

    // ───────────────────────── /skill 管理 ─────────────────────────

    @Test
    @DisplayName("/skill list：列出已加载 Skill 与来源")
    void skillListShowsSource() throws IOException {
        writeSkill("commit", "commit", "生成规范的 commit", "## Commit SOP");
        reload();

        String out = dispatch("/skill");
        assertTrue(out.contains("已加载 Skill（1 个）"), out);
        assertTrue(out.contains("/commit"), out);
        assertTrue(out.contains("项目级"), out);
    }

    @Test
    @DisplayName("没有装任何 Skill 时，/skill 直接告诉用户往哪放")
    void skillListWhenEmpty() {
        String out = dispatch("/skill");
        assertTrue(out.contains("还没有安装任何 Skill"), out);
        assertTrue(out.contains(".agentjava"), out);
        assertTrue(out.contains("skills"), out);
    }

    @Test
    @DisplayName("/skill info 给出模式与目录；名字带斜杠也认")
    void skillInfoAcceptsLeadingSlash() throws IOException {
        writeSkill("commit", "commit", "生成规范的 commit", "## Commit SOP");
        reload();

        String info = dispatch("/skill info /commit");
        assertTrue(info.contains("/commit — 生成规范的 commit"), info);
        assertTrue(info.contains("模式：inline"), info);
        assertTrue(info.contains("来源：项目级"), info);

        assertTrue(dispatch("/skill info").contains("用法：/skill info"), dispatch("/skill info"));
        assertTrue(dispatch("/skill info nope").contains("找不到 Skill: nope"));
        assertTrue(dispatch("/skill wat").contains("未知子命令：wat"));
    }

    @Test
    @DisplayName("/skill reload：新放进目录的 Skill 立即可用，删掉的也真的消失")
    void skillReloadPicksUpChanges() throws IOException {
        String first = dispatch("/skill reload");
        assertTrue(first.contains("共 0 个"), first);

        writeSkill("commit", "commit", "生成规范的 commit", "## Commit SOP");
        String second = dispatch("/skill reload");
        assertTrue(second.contains("共 1 个"), second);
        assertTrue(dispatch("/commit").contains("## Commit SOP"));

        Files.delete(project.resolve(".agentjava").resolve("skills")
                .resolve("commit").resolve("SKILL.md"));
        Files.delete(project.resolve(".agentjava").resolve("skills").resolve("commit"));
        assertTrue(dispatch("/skill reload").contains("共 0 个"));
    }

    @Test
    @DisplayName("fork 模式的 Skill 走隔离子 Agent，SOP 不进主对话")
    void forkSkillRunsInIsolatedContext() throws IOException {
        Path dir = project.resolve(".agentjava").resolve("skills").resolve("audit");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: audit\ndescription: 隔离审查\nmode: fork\nfork_context: none\n---\n\n## 隔离审查 SOP");
        reload();

        String out = dispatch("/audit");
        // 子 Agent 起不来（provider 指向 127.0.0.1:1），但重要的是它确实走了 fork 分支
        assertTrue(out.contains("fork 执行失败"), out);
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("## 隔离审查 SOP"), out);
    }

    // ───────────────────────── 辅助 ─────────────────────────

    private void writeSkill(String dirName, String name, String description, String body)
            throws IOException {
        Path dir = project.resolve(".agentjava").resolve("skills").resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body);
    }

    private void reload() {
        // 直接敲命令走的是同一条路径，比调内部方法更能反映真实行为
        String out = dispatch("/skill reload");
        assertTrue(out.contains("已重新加载"), out);
    }
}
