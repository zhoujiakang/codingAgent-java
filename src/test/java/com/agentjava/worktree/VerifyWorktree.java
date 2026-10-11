package com.agentjava.worktree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agentjava.agent.AgentEvent;
import com.agentjava.config.ProviderConfig;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.llm.LlmClient;
import com.agentjava.llm.StreamEvent;
import com.agentjava.subagent.AgentDefinition;
import com.agentjava.subagent.AgentTool;
import com.agentjava.subagent.SubAgentTaskManager;
import com.agentjava.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线验证：Slug 安全校验 → 变更检测 fail-closed → 临时命名识别 →
 * WorktreeManager 创建/快速恢复/列出/删除 生命周期（真实 git 仓库）。
 *
 * <p>纯逻辑（slug、变更、命名模式）走单元测试，不依赖 git；生命周期测试在
 * {@link TempDir} 里 {@code git init} 出一个真实仓库再操作，覆盖快速恢复与变更保护。
 */
class VerifyWorktree {

    // ───────────────────────── Slug 安全校验 ─────────────────────────

    @Test
    @DisplayName("Slug：合法命名能过，路径穿越与非法字符被拒")
    void slugValidation() {
        // 合法
        SlugValidator.validate("feature-x");
        SlugValidator.validate("team-refactor/alice"); // 嵌套命名
        SlugValidator.validate("1.2_beta-test");

        // 路径穿越
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("a/../b"));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("/abs"));
        // 非法字符
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("has space"));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("star*"));
        // 长度上限
        assertThrows(IllegalArgumentException.class,
                () -> SlugValidator.validate("x".repeat(SlugValidator.MAX_LENGTH + 1)));
        // 多余斜杠 / 空段
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("a/"));
        assertThrows(IllegalArgumentException.class, () -> SlugValidator.validate("a//b"));
    }

    @Test
    @DisplayName("Slug：flatten 与 branchName 转换正确")
    void slugFlatten() {
        assertEquals("team-refactor+alice", SlugValidator.flatten("team-refactor/alice"));
        assertEquals("worktree-team-refactor+alice", SlugValidator.branchName("team-refactor/alice"));
        assertEquals("worktree-feature", SlugValidator.branchName("feature"));
    }

    // ───────────────────────── 临时命名识别 ─────────────────────────

    @Test
    @DisplayName("临时 Worktree 命名模式：子 Agent 生成的会被回收，手动命名不会")
    void ephemeralPatterns() {
        assertTrue(AgentWorktree.isEphemeral("agent-a1a2b3c4"));
        assertTrue(StaleCleanup.isEphemeral("wf_12345678-abc-1"));
        assertTrue(StaleCleanup.isEphemeral("wf-7"));
        assertTrue(StaleCleanup.isEphemeral("bridge-build-x"));
        assertTrue(StaleCleanup.isEphemeral("job-feature-abc-12345678"));
        // 用户手动命名
        assertFalse(AgentWorktree.isEphemeral("my-feature"));
        assertFalse(AgentWorktree.isEphemeral("refactor-worker"));
        assertFalse(AgentWorktree.isEphemeral("agent-a")); // 只有 7 位 hex 才匹配
    }

    // ───────────────────────── 变更检测 fail-closed ─────────────────────────

    @Test
    @DisplayName("变更检测：基线缺失/检测失败时一律当「有变更」（fail-closed）")
    void changeDetectionFailClosed(@TempDir Path dir) {
        // 非法基线 SHA → 检测未知 → 当「有变更」
        assertTrue(WorktreeChanges.hasChanges(dir.toString(), "not-a-sha"));
        assertTrue(WorktreeChanges.countChanges(dir.toString(), "not-a-sha") == null);
        // 空基线 → fail-closed
        assertTrue(WorktreeChanges.hasChanges(dir.toString(), null));
        // 合法但不存在的目录：status 会失败 → 当「有变更」
        assertTrue(WorktreeChanges.hasChanges(dir.resolve("nope").toString(), "abc1234"));
    }

    // ───────────────────────── 生命周期（真实 git 仓库） ─────────────────────────

    @Test
    @DisplayName("WorktreeManager：创建 → 列出 → 快速恢复 → 变更保护 → 删除")
    void lifecycle(@TempDir Path repo) throws IOException {
        initRepo(repo);

        var mgr = new WorktreeManager(repo.toString());

        // 1) 创建
        var info = mgr.create("feat-1", "HEAD");
        assertNotNull(info);
        assertTrue(Files.isDirectory(repo.resolve(".agentjava/worktrees/feat-1")));
        assertNotNull(info.headCommit());
        assertFalse(info.headCommit().isBlank());

        // 2) 列出含该 worktree
        // 注意：macOS 上 /tmp、/var 是软链到 /private/*，git 汇报的是规范化后的真实路径，
        // 与 Java 拿到的 /var/... 字符串不同，故用 toRealPath 做软链无关的等价比较。
        Path expectedWt = repo.resolve(".agentjava/worktrees/feat-1").toRealPath();
        boolean listed = mgr.list().stream()
                .anyMatch(w -> {
                    try {
                        return Path.of(w.path()).toRealPath().equals(expectedWt);
                    } catch (IOException e) {
                        return false;
                    }
                });
        assertTrue(listed, "list() 应包含刚创建的 worktree");

        // 3) 快速恢复：同名再创建应复用目录，不重新 git add（headCommit 仍可拿到）
        var info2 = mgr.create("feat-1", "HEAD");
        assertEquals(info.path(), info2.path());
        assertNotNull(info2.headCommit());

        // 4) 变更保护：worktree 里改个文件后，remove 不带 discard 应被拒
        Path wtFile = repo.resolve(".agentjava/worktrees/feat-1/changed.txt");
        Files.writeString(wtFile, "hello");
        assertThrows(IllegalStateException.class, () -> mgr.remove("feat-1", "remove", false));

        // 带 discard 则强删成功
        mgr.remove("feat-1", "remove", true);
        assertFalse(Files.isDirectory(repo.resolve(".agentjava/worktrees/feat-1")));
    }

    @Test
    @DisplayName("WorktreeManager：干净 worktree 的 remove 无需 discard")
    void removeCleanWithoutDiscard(@TempDir Path repo) throws IOException {
        initRepo(repo);
        var mgr = new WorktreeManager(repo.toString());
        mgr.create("clean-wt", "HEAD");
        // 不做任何修改 → 干净 → 不带 discard 也能删
        mgr.remove("clean-wt", "remove", false);
        assertFalse(Files.isDirectory(repo.resolve(".agentjava/worktrees/clean-wt")));
    }

    // ───────────────────────── 会话持久化 ─────────────────────────

    @Test
    @DisplayName("WorktreeSessionStore：保存后读取一致，保存 null 即删除")
    void sessionPersistence(@TempDir Path repo) throws IOException {
        var s = new WorktreeSession("/proj", "/proj/.agentjava/worktrees/feat",
                "feat", "worktree-feat", "main", "abc1234", "sess-1", 12L);
        WorktreeSessionStore.save(repo.toString(), s);
        var loaded = WorktreeSessionStore.load(repo.toString());
        assertTrue(loaded.isPresent());
        assertEquals("worktree-feat", loaded.get().worktreeBranch());
        assertTrue(loaded.get().isActive());

        WorktreeSessionStore.save(repo.toString(), null);
        assertTrue(WorktreeSessionStore.load(repo.toString()).isEmpty());
    }

    // ───────────────────────── 辅助：初始化一个带提交的 git 仓库 ─────────────────────────

    private void initRepo(Path repo) throws IOException {
        GitUtil.run(repo.toString(), "init");
        GitUtil.run(repo.toString(), "config", "user.email", "test@agentjava.dev");
        GitUtil.run(repo.toString(), "config", "user.name", "AgentJava Test");
        Files.writeString(repo.resolve("README.md"), "# test repo\n");
        GitUtil.run(repo.toString(), "add", "-A");
        GitUtil.run(repo.toString(), "commit", "-m", "init");
    }

    // ───────────────────────── 集成冒烟：isolation=worktree 端到端 ─────────────────────────

    /**
     * 端到端冒烟：AgentTool 收到 {@code isolation=worktree} 后应当
     * ① 在真实 git 仓库里建出隔离副本；② 把该副本路径作为子 Agent 的 workDir 传下去；
     * ③ 子 Agent 跑完（LoopComplete）后自动清理干净副本。这等价于在 pty 里手动驱动
     * 「/worktree + 派发隔离子 Agent」的链路，只是用假任务管理器把 Agent Loop 短路掉。
     */
    @Test
    @DisplayName("集成冒烟：AgentTool isolation=worktree 创建隔离副本、作为子 Agent workDir、跑完自动清理")
    void isolationWorktreeEndToEnd(@TempDir Path repo) throws IOException {
        initRepo(repo);

        var wtm = new WorktreeManager(repo.toString());
        var registry = new ToolRegistry();

        // 假客户端：子 Agent 实际不会跑（任务管理器短路），只要求非 null。
        LlmClient client = new LlmClient() {
            @Override
            public BlockingQueue<StreamEvent> stream(ConversationManager conv) {
                return new LinkedBlockingQueue<>();
            }

            @Override
            public BlockingQueue<StreamEvent> stream(ConversationManager conv,
                                                    List<Map<String, Object>> tools) {
                return new LinkedBlockingQueue<>();
            }

            @Override
            public ProviderConfig config() {
                return null;
            }

            @Override
            public void setSystemPrompt(String prompt) {
            }
        };

        // 捕获型任务管理器：launch 时记录子 Agent 的 workDir，并模拟 LoopComplete 触发自动清理。
        final String[] capturedWorkDir = {null};
        final boolean[] existedWhenCaptured = {false};
        SubAgentTaskManager manager = new SubAgentTaskManager() {
            @Override
            public void launch(String taskId, SubAgentTaskManager.SpawnRequest req) {
                capturedWorkDir[0] = req.workDir();
                existedWhenCaptured[0] = Files.isDirectory(Path.of(req.workDir()));
                if (req.listener() != null) {
                    req.listener().accept(new AgentEvent.LoopComplete(1));
                }
                setCompleted(taskId, "smoke-ok");
            }
        };

        var tool = new AgentTool(registry);
        tool.setAgentDefinitions(builtinsMap());
        tool.setClient(client);
        tool.setTaskManager(manager);
        tool.setWorkDir(repo.toString());
        tool.setPermissionMode(com.agentjava.permission.PermissionMode.BYPASS);
        tool.setWorktreeManager(wtm);

        var result = tool.execute(Map.of(
                "description", "冒烟子任务", "prompt", "做点什么", "isolation", "worktree"));

        // ① 隔离副本确实被创建
        assertNotNull(capturedWorkDir[0]);
        assertTrue(existedWhenCaptured[0], "launch 时隔离 worktree 目录应已存在");
        assertTrue(capturedWorkDir[0].contains(".agentjava/worktrees/"),
                "子 Agent 的 workDir 应是隔离 worktree 路径：" + capturedWorkDir[0]);
        // ② 结果成功且表明走了 worktree 隔离
        assertTrue(!result.isError(), "isolation=worktree 应成功，实际：" + result.output());
        assertTrue(result.output().contains("worktree 隔离"));
        // ③ 干净副本在跑完后被自动清理
        assertFalse(Files.isDirectory(Path.of(capturedWorkDir[0])),
                "干净 worktree 应在子 Agent 结束后被自动清理");
    }

    /** 取内置三件套定义表，供冒烟测试路由 isolation=worktree 时解析子 Agent 定义。 */
    private static java.util.LinkedHashMap<String, AgentDefinition> builtinsMap() {
        var map = new java.util.LinkedHashMap<String, AgentDefinition>();
        for (var def : AgentDefinition.builtins()) {
            map.put(def.agentType(), def);
        }
        return map;
    }
}
