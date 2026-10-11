package com.agentjava.worktree;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Worktree 会话的持久化层。
 *
 * <p>把 {@link WorktreeSession} 写到 {@code <repoRoot>/.agentjava/worktree_session.json}，
 * 进程意外退出后下次启动可据此 {@code --resume} 恢复，直接回到上次所在的 Worktree，
 * 跳过重新创建。写 {@code null} 会删除该文件——这是退出时最关键的一步：
 * 不清的话，下次启动会误以为还停留在某个可能已经不存在的 Worktree 里。
 *
 * <p>全局单例用 {@code volatile} 声明，保证多线程可见性；读取方通过
 * {@link #getCurrentSession()} 拿到当前路径。它与进程 cwd 解耦，解决「全局可变状态
 * 导致并发踩踏」的问题：每个 Agent 跑在自己的线程上，各自从自己的 workDir 取路径，
 * 不会去争抢这一个单例。
 */
public final class WorktreeSessionStore {

    /** 配置目录名（本项目约定 {@code .agentjava}）。 */
    public static final String CONFIG_DIR = ".agentjava";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 当前会话（volatile：跨线程可见）。 */
    private static volatile WorktreeSession current = null;

    private WorktreeSessionStore() {
    }

    /** 会话文件路径：{@code <repoRoot>/.agentjava/worktree_session.json}。 */
    public static Path sessionPath(String repoRoot) {
        return Path.of(repoRoot, CONFIG_DIR, "worktree_session.json");
    }

    /** 持久化一个会话；传 {@code null} 表示清除（删除文件）。 */
    public static void save(String repoRoot, WorktreeSession session) throws IOException {
        Path path = sessionPath(repoRoot);
        if (session == null) {
            Files.deleteIfExists(path);
            current = null;
            return;
        }
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), session);
        current = session;
    }

    /** 从磁盘读取会话；文件不存在或解析失败返回 {@code Optional.empty()}。 */
    public static Optional<WorktreeSession> load(String repoRoot) {
        Path path = sessionPath(repoRoot);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            WorktreeSession s = MAPPER.readValue(path.toFile(), WorktreeSession.class);
            current = s;
            return Optional.of(s);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** 进程内当前会话（可能为 null）。 */
    public static WorktreeSession getCurrentSession() {
        return current;
    }

    /** 恢复为指定会话（仅更新内存态，不写盘）。 */
    public static void restoreSession(WorktreeSession session) {
        current = session;
    }
}
