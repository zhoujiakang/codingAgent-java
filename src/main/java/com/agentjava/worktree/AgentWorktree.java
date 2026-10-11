package com.agentjava.worktree;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 子 Agent 专用的 Worktree 轻量入口。
 *
 * <p>与 {@link WorktreeManager} 的区别：它<b>不碰全局会话状态</b>（不写
 * {@code WorktreeSessionStore}），只做「创建 + 返回结果」和「删除」两件事，
 * 适合高频的子 Agent 场景——每次派发都是一次独立、隔离的副本。
 *
 * <p>临时 Worktree 的命名固定为 {@code agent-a} + 7 位随机 hex，匹配
 * {@link StaleCleanup#EPHEMERAL_PATTERNS} 里的模式，因此会被后台过期清理自动回收，
 * 而用户用 {@code /worktree create} 手动命名的则永远不会被自动清理。
 */
public final class AgentWorktree {

    private AgentWorktree() {
    }

    /** 创建结果的快照，供子 Agent 在隔离副本里工作。 */
    public record Result(
            String worktreePath,
            String worktreeBranch,
            String headCommit,  // 创建时的 HEAD SHA，作为变更检测基线
            String gitRoot
    ) {
    }

    /**
     * 为子 Agent 创建一个临时 Worktree。
     *
     * @param manager    通用管理器（负责真正落盘的 git 操作与初始化）
     * @param baseBranch 基线分支，默认 {@code HEAD}
     * @return 创建结果；名称由本方法内部生成（{@code agent-a} + 7 位随机 hex）
     */
    public static Result create(WorktreeManager manager, String baseBranch) {
        String suffix = String.format("%07x", ThreadLocalRandom.current().nextInt(0x10000000));
        String name = "agent-a" + suffix;
        WorktreeManager.WorktreeInfo info = manager.create(name, baseBranch);
        return new Result(info.path(), info.branch(), info.headCommit(), manager.projectRoot());
    }

    /**
     * 删除一个 Worktree：{@code git worktree remove --force} 后等 lockfile 释放再
     * {@code git branch -D}。两条命令间 {@code sleep(100)} 是经验值。
     */
    public static boolean remove(String worktreePath, String worktreeBranch, String gitRoot) {
        String root = gitRoot != null && !gitRoot.isBlank() ? gitRoot : worktreePath;
        boolean removed = GitUtil.runQuiet(root, "worktree", "remove", "--force", worktreePath);
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worktreeBranch != null && !worktreeBranch.isBlank()) {
            GitUtil.runQuiet(root, "branch", "-D", worktreeBranch);
        }
        return removed;
    }

    /**
     * 自动清理：无变更就删除，有变更就保留并把路径与分支名返回给主 Agent review。
     *
     * @return 一段给主 Agent 看的结果说明（保留或已清理）
     */
    public static String autoCleanup(String worktreePath, String worktreeBranch,
                                    String headCommit, String gitRoot) {
        if (WorktreeChanges.hasChanges(worktreePath, headCommit)) {
            return "\n[Worktree 保留在 " + worktreePath + "，分支 " + worktreeBranch
                    + "：检测到未提交的修改或新 commit，请 review 后自行合并/丢弃]";
        }
        remove(worktreePath, worktreeBranch, gitRoot);
        return "\n[Worktree 已自动清理（无变更）]";
    }

    /**
     * 生成注入子 Agent 提示词的上下文通知。
     *
     * <p>告诉子 Agent 三件事：你继承了父 Agent 的对话上下文；你当前在一个独立的 git
     * worktree 中工作；父 Agent 传来的路径指向父目录，你需要翻译成本地路径，且编辑前
     * 要重新读取文件（因为父 Agent 可能已经改过）。没有这段通知，子 Agent 可能直接拿
     * 父目录的绝对路径去读写，绕过了隔离。
     */
    public static String buildNotice(String parentCwd, String worktreeCwd) {
        return "You've inherited the conversation context above from a parent agent "
                + "working in " + parentCwd + ". "
                + "You are operating in an isolated git worktree at " + worktreeCwd + ". "
                + "Files you read or write live ONLY in this worktree copy. "
                + "Any absolute or relative paths from the parent refer to " + parentCwd
                + "— translate them to " + worktreeCwd + " before using. "
                + "Re-read any file before editing it, since the parent may have modified it.";
    }

    /** 名称是否匹配临时 Worktree 模式（即是否可被后台清理自动回收）。 */
    public static boolean isEphemeral(String slug) {
        return StaleCleanup.isEphemeral(slug);
    }
}
