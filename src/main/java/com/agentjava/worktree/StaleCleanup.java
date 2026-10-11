package com.agentjava.worktree;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 过期临时 Worktree 的后台清理。
 *
 * <p>子 Agent 异常退出（进程崩溃、被强杀）会留下孤儿 Worktree。时间一长，
 * {@code .agentjava/worktrees/} 下会堆满无用目录。清理靠<b>命名模式</b>区分：
 * 临时 Worktree 用 {@code agent-a} + 7 位 hex 等固定格式，用户手动
 * {@code /worktree create my-feature} 起的名字不会命中任何模式，永远不会被自动清理。
 *
 * <p>清理漏斗三层递进：
 * <ol>
 *   <li><b>模式过滤</b>：只碰 ephemeral 命名的目录；</li>
 *   <li><b>年龄过滤</b>：跳过当前会话中的、以及修改时间未过期的；</li>
 *   <li><b>变更检查（fail-closed）</b>：有未提交修改、或未推送的 commit 都跳过。</li>
 * </ol>
 * 核心原则：宁可多占磁盘，也不丢失可能有价值的工作成果。
 */
public final class StaleCleanup {

    /** 临时 Worktree 的命名模式集合（覆盖所有自动生成的命名）。 */
    public static final List<Pattern> EPHEMERAL_PATTERNS = List.of(
            Pattern.compile("^agent-a[0-9a-f]{7}$"),
            Pattern.compile("^wf_[0-9a-f]{8}-[0-9a-f]{3}-\\d+$"),
            Pattern.compile("^wf-\\d+$"),
            Pattern.compile("^bridge-[A-Za-z0-9_]+(-[A-Za-z0-9_]+)*$"),
            Pattern.compile("^job-[a-zA-Z0-9._-]{1,55}-[0-9a-f]{8}$")
    );

    private StaleCleanup() {
    }

    /** 名称是否匹配临时 Worktree 模式。 */
    public static boolean isEphemeral(String slug) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        for (Pattern p : EPHEMERAL_PATTERNS) {
            if (p.matcher(slug).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 扫描并清理过期的临时 Worktree。
     *
     * @param repoRoot     主仓库根目录
     * @param worktreeDir  worktree 存放目录（绝对路径）
     * @param cutoff       过期阈值：mtime 晚于它的「还新鲜」，跳过
     */
    public static void cleanup(String repoRoot, String worktreeDir, Instant cutoff) {
        Path dir = Path.of(worktreeDir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        String currentPath = null;
        WorktreeSession session = WorktreeSessionStore.getCurrentSession();
        if (session != null) {
            currentPath = session.worktreePath();
        }

        Path[] entries;
        try (var stream = Files.newDirectoryStream(dir)) {
            var list = new java.util.ArrayList<Path>();
            stream.forEach(list::add);
            entries = list.toArray(new Path[0]);
        } catch (java.io.IOException e) {
            return;
        }

        for (Path entry : entries) {
            if (!Files.isDirectory(entry)) {
                continue;
            }
            String name = entry.getFileName().toString();
            // Layer 1：只清理临时 Worktree
            if (!isEphemeral(name)) {
                continue;
            }
            String wtPath = entry.toAbsolutePath().normalize().toString();
            // 跳过当前正在使用的会话
            if (wtPath.equals(currentPath)) {
                continue;
            }
            // Layer 2：跳过未过期的
            try {
                Instant mtime = Files.getLastModifiedTime(entry).toInstant();
                if (mtime.isAfter(cutoff)) {
                    continue;
                }
            } catch (java.io.IOException e) {
                continue;
            }
            // Layer 3：fail-closed 变更检查
            // 第一层：未提交修改（-uno 跳过 untracked 扫描提速）；命令失败当「有变更」
            String statusOut = GitUtil.run(wtPath, "--no-optional-locks",
                    "status", "--porcelain", "-uno");
            if (statusOut == null || !statusOut.isBlank()) {
                continue;
            }
            // 第二层：有未推送 commit 也跳过
            String unpushed = GitUtil.run(wtPath, "rev-list", "--max-count=1",
                    "HEAD", "--not", "--remotes");
            if (unpushed == null || !unpushed.isBlank()) {
                continue;
            }
            // 通过全部检查 → 删除
            String branch = SlugValidator.branchName(name);
            GitUtil.runQuiet(repoRoot, "worktree", "remove", "--force", wtPath);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            GitUtil.runQuiet(repoRoot, "branch", "-D", branch);
        }
    }

    /**
     * 启动定时清理循环（由调用方持有并负责关闭 {@link ScheduledExecutorService}）。
     *
     * @param executor          调度器（单线程即可）
     * @param repoRoot          主仓库根目录
     * @param worktreeDir       worktree 存放目录
     * @param intervalSeconds   扫描间隔（秒）
     * @param cutoffHours       过期阈值（小时）：修改时间早于「现在 - cutoffHours」才清理
     */
    public static void startCleanupLoop(ScheduledExecutorService executor,
                                        String repoRoot, String worktreeDir,
                                        int intervalSeconds, int cutoffHours) {
        long cutoffSec = (cutoffHours > 0 ? cutoffHours : 24) * 3600L;
        executor.scheduleAtFixedRate(() -> {
            try {
                Instant cutoff = Instant.now().minusSeconds(cutoffSec);
                cleanup(repoRoot, worktreeDir, cutoff);
            } catch (RuntimeException ignored) {
                // 后台循环绝不能因为一次异常挂掉
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /** 便捷方法：创建一个用于定时清理的单线程调度器。 */
    public static ScheduledExecutorService newCleanupExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "worktree-stale-cleanup");
            t.setDaemon(true);
            return t;
        });
    }
}
