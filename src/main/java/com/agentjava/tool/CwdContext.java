package com.agentjava.tool;

import java.io.File;
import java.nio.file.Path;

/**
 * 当前工作目录的线程局部载体。
 *
 * <p>Worktree 隔离的核心约束之一是<b>不改进程级 cwd</b>——进程 cwd 是全局可变状态，
 * 多个并发的 Agent（后台子 Agent、Agent Team 队员）之间会因为「谁的 chdir 先谁的后」
 * 而互相踩踏。这里用线程局部把「当前工作目录」保存在每个执行线程上，工具调用时
 * 显式地从这个上下文取 cwd，而不是依赖进程 cwd。
 *
 * <p>为什么用线程局部能成立：每个 Agent 的 ReAct 主循环跑在独立的虚拟线程上，
 * 工具执行又集中在 {@code StreamingExecutor.executeSingle} 里——它在真正调用
 * {@code tool.execute} 之前把当前 Agent 的 {@code workDir} 写进这个线程局部，调用结束后还原。
 * 即便是 READ 批次里并发跑的多个虚拟线程，每一条也都各自拥有正确的 cwd，互不干扰。
 *
 * <p>路径解析规则：
 * <ul>
 *   <li>已经是绝对路径：原样返回；</li>
 *   <li>相对路径 + 有 cwd：以 cwd 为基解析后 {@code normalize()}；</li>
 *   <li>相对路径 + 无 cwd：退回 JVM 的 {@code user.dir}（等价于不设置时的旧行为）。</li>
 * </ul>
 *
 * 因为缓存按绝对路径做 key，Worktree 里的 {@code server.py} 与主目录里的 {@code server.py}
 * 天然是不同的 key，所以进入/退出 Worktree 时<b>不需要清缓存</b>。
 */
public final class CwdContext {

    private static final ThreadLocal<String> CWD = new ThreadLocal<>();

    private CwdContext() {
    }

    /**
     * 设置当前线程的工作目录（绝对路径）。传 {@code null} 表示撤掉覆盖，退回 JVM {@code user.dir}。
     */
    public static void set(String cwd) {
        if (cwd == null) {
            CWD.remove();
        } else {
            CWD.set(cwd);
        }
    }

    /** 读取当前线程的工作目录；未设置时返回 {@code null}。 */
    public static String get() {
        return CWD.get();
    }

    /**
     * 把工具收到的（可能相对的）路径，解析成基于当前 cwd 的绝对路径。
     */
    public static Path resolve(Path p) {
        String cwd = CWD.get();
        if (cwd == null || p.isAbsolute()) {
            return p;
        }
        return Path.of(cwd).resolve(p).normalize();
    }

    /**
     * 供 {@code ProcessBuilder.directory(...)} 使用：当前 cwd 对应的目录对象，
     * 未设置时返回 {@code null}（调用方应据此「不设置 directory」，沿用进程 cwd）。
     */
    public static File dirOrNull() {
        String cwd = CWD.get();
        return cwd == null ? null : Path.of(cwd).toFile();
    }
}
