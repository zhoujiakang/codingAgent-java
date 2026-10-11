package com.agentjava.worktree;

import java.util.regex.Pattern;

/**
 * Worktree 变更检测，统一遵循 <b>fail-closed</b> 语义。
 *
 * <p>退出/清理前必须确认 Worktree 里没有有价值的工作成果。检测分两层：
 * <ol>
 *   <li>{@code git status --porcelain}：未提交的修改；</li>
 *   <li>{@code git rev-list --count <headCommit>..HEAD}：相对创建基线的新增 commit。</li>
 * </ol>
 *
 * 两层任一判断失败（命令执行失败、解析异常、基线缺失）一律<b>当成「有变更」</b>——
 * 宁可多留一个目录，绝不因为一次误判把子 Agent 几小时的工作抹掉。
 */
public final class WorktreeChanges {

    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-f]{7,40}$");

    private WorktreeChanges() {
    }

    /** 是否有任意变更：未提交修改或新增 commit（fail-closed：检测失败当「有变更」）。 */
    public static boolean hasChanges(String worktreePath, String headCommit) {
        ChangeSummary s = countChanges(worktreePath, headCommit);
        return s == null || !s.clean();
    }

    /**
     * 精确统计变更量。
     *
     * @return 含未提交文件数与新增 commit 数的快照；无基线（headCommit 为空）或检测失败时返回 {@code null}
     *         （调用方必须把它当「有变更」处理）。
     */
    public static ChangeSummary countChanges(String worktreePath, String headCommit) {
        if (headCommit == null || headCommit.isBlank() || !SHA_PATTERN.matcher(headCommit.strip()).matches()) {
            return null;
        }
        try {
            String statusOut = GitUtil.run(worktreePath, "status", "--porcelain");
            if (statusOut == null || !statusOut.isBlank()) {
                // null=命令失败 → 有变更；非空=有未提交修改 → 有变更
                if (statusOut == null) {
                    return new ChangeSummary(1, 0); // fail-closed：未知但保守
                }
                long changed = statusOut.lines().filter(l -> !l.isBlank()).count();
                if (changed > 0) {
                    return new ChangeSummary(changed, 0);
                }
            }
            String revOut = GitUtil.run(worktreePath, "rev-list", "--count",
                    headCommit.strip() + "..HEAD");
            if (revOut == null) {
                return new ChangeSummary(0, 1); // fail-closed
            }
            long commits = parseLongOr(revOut.strip(), 1);
            if (commits > 0) {
                return new ChangeSummary(0, commits);
            }
            return new ChangeSummary(0, 0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long parseLongOr(String s, long fallback) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 变更统计快照。 */
    public record ChangeSummary(long changedFiles, long commits) {
        /** 是否「干净」（无未提交修改、无新增 commit）。 */
        public boolean clean() {
            return changedFiles == 0 && commits == 0;
        }
    }
}
