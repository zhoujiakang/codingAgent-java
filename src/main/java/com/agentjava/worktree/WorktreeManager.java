package com.agentjava.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Worktree 通用入口：创建、列出、删除、过期清理。
 *
 * <p>所有公共方法加 {@code synchronized}——Worktree 操作频率很低，粗粒度锁完全够用，
 * 没必要引入更复杂的并发控制。内存里维护一份 {@code worktrees} 字典（保持插入顺序，
 * 让 {@code list()} 结果与创建顺序一致），落盘的真相在 {@code git worktree list}。
 *
 * <p>目录约定：Worktree 统一放在仓库内的 {@code <repoRoot>/.agentjava/worktrees/} 下，
 * 该目录已在 {@code .gitignore} 中，不会被 Git 追踪。
 */
public class WorktreeManager {

    /** 配置目录名。 */
    public static final String CONFIG_DIR = WorktreeSessionStore.CONFIG_DIR;

    /** 单个 Worktree 的元数据。 */
    public record WorktreeInfo(
            String path,        // worktree 目录绝对路径
            String branch,      // 对应的 git 分支名
            String headCommit,  // 创建时的 HEAD SHA，作为变更检测基线
            Instant createdAt   // 创建时间，用于过期判断
    ) {
    }

    private final String projectRoot;
    private final List<String> symlinkDirs;
    private final int staleCutoffHours;

    /** 保持插入顺序：list() 结果与创建顺序一致。 */
    private final Map<String, WorktreeInfo> worktrees = new LinkedHashMap<>();

    public WorktreeManager(String projectRoot) {
        this(projectRoot, List.of(), 24);
    }

    public WorktreeManager(String projectRoot, List<String> symlinkDirs, int staleCutoffHours) {
        this.projectRoot = Path.of(projectRoot).toAbsolutePath().normalize().toString();
        this.symlinkDirs = symlinkDirs == null ? List.of() : List.copyOf(symlinkDirs);
        this.staleCutoffHours = staleCutoffHours > 0 ? staleCutoffHours : 24;
    }

    public String projectRoot() {
        return projectRoot;
    }

    /** Worktree 存放目录：{@code <repoRoot>/.agentjava/worktrees}。 */
    public Path worktreeDir() {
        return Path.of(projectRoot, CONFIG_DIR, "worktrees");
    }

    /**
     * 创建一个 Worktree。
     *
     * <p>流程：校验 slug → 计算路径与分支 →（目录已存在则<b>快速恢复</b>，纯文件系统读取，
     * 不跑 git 子进程）→ 否则 {@code git worktree add -B} 后再做四步初始化 → 记录状态。
     */
    public synchronized WorktreeInfo create(String name, String baseBranch) {
        SlugValidator.validate(name);

        String flat = SlugValidator.flatten(name);
        String branch = SlugValidator.branchName(name);
        Path wtPath = worktreeDir().resolve(flat).toAbsolutePath().normalize();
        String base = (baseBranch == null || baseBranch.isBlank()) ? "HEAD" : baseBranch;

        // 快速恢复：目录已存在就直接复用，省掉 git worktree add 的数秒开销
        if (Files.isDirectory(wtPath)) {
            try {
                Files.setLastModifiedTime(wtPath, java.nio.file.attribute.FileTime
                        .from(Instant.now()));
            } catch (IOException ignored) {
                // 刷新 mtime 失败不影响复用
            }
            String head = readHead(wtPath.toString());
            return new WorktreeInfo(wtPath.toString(), branch,
                    head != null ? head : "", Instant.now());
        }

        GitUtil.runQuiet(projectRoot, "worktree", "add", "-B", branch,
                wtPath.toString(), base);
        PostCreationSetup.perform(projectRoot, wtPath.toString(), symlinkDirs);

        String head = readHead(wtPath.toString());
        var info = new WorktreeInfo(wtPath.toString(), branch,
                head != null ? head : "", Instant.now());
        worktrees.put(name, info);
        return info;
    }

    /** 列出受管理的 Worktree（路径落在 {@code worktreeDir} 下的那些）。 */
    public synchronized List<WorktreeInfo> list() {
        var out = new ArrayList<WorktreeInfo>();
        String out2 = GitUtil.run(projectRoot, "worktree", "list", "--porcelain");
        if (out2 == null) {
            return out;
        }
        Path base = worktreeDir().toAbsolutePath().normalize();
        // macOS 上 /tmp、/var 是软链到 /private/*，git 汇报的是真实路径；这里把 base 也解析成
        // 真实路径，否则前缀比较会因 /var 与 /private/var 不一致而把合法 worktree 误过滤掉。
        try {
            base = base.toRealPath();
        } catch (IOException ignored) {
            // 解析失败就用规范化后的原始路径兜底
        }
        String[] blocks = out2.split("(?<=\n)(?=worktree )");
        for (String block : blocks) {
            String path = null;
            String branch = "";
            String head = "";
            for (String line : block.split("\n")) {
                if (line.startsWith("worktree ")) {
                    path = line.substring("worktree ".length()).strip();
                } else if (line.startsWith("branch ")) {
                    String ref = line.substring("branch ".length()).strip();
                    int idx = ref.lastIndexOf('/');
                    branch = idx >= 0 ? ref.substring(idx + 1) : ref;
                } else if (line.startsWith("HEAD ")) {
                    head = line.substring("HEAD ".length()).strip();
                }
            }
            if (path == null) {
                continue;
            }
            Path p = Path.of(path).toAbsolutePath().normalize();
            try {
                p = p.toRealPath();
            } catch (IOException ignored) {
                // 解析失败就用规范化后的原始路径兜底
            }
            if (!p.startsWith(base)) {
                continue; // 跳过主仓库自身
            }
            out.add(new WorktreeInfo(p.toString(), branch, head, Instant.EPOCH));
        }
        return out;
    }

    /**
     * 删除一个 Worktree。
     *
     * @param action   目前支持 {@code "remove"}（其余当不做处理）
     * @param discard  是否连同未提交的修改/新 commit 一起丢弃；为 false 且存在变更时抛异常
     * @throws IllegalStateException 有未保存变更且未显式 discard
     */
    public synchronized void remove(String name, String action, boolean discard)
            throws IllegalStateException {
        if (!"remove".equalsIgnoreCase(action)) {
            return;
        }
        String flat = SlugValidator.flatten(name);
        String branch = SlugValidator.branchName(name);
        Path wtPath = worktreeDir().resolve(flat).toAbsolutePath().normalize();

        WorktreeInfo info = worktrees.get(name);
        String headCommit = info != null ? info.headCommit() : null;

        if (!discard) {
            // fail-closed：检测未知（null）一律当「有变更」处理，绝不在未确认时误删
            WorktreeChanges.ChangeSummary changes = WorktreeChanges.countChanges(
                    wtPath.toString(), headCommit);
            if (changes == null || !changes.clean()) {
                throw new IllegalStateException("worktree 存在未提交的修改或新 commit，"
                        + "需显式传 discard=true 才能强制删除");
            }
        }

        GitUtil.runQuiet(projectRoot, "worktree", "remove", "--force", wtPath.toString());
        try {
            Thread.sleep(100); // 等 git lockfile 释放
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (branch != null && !branch.isBlank()) {
            GitUtil.runQuiet(projectRoot, "branch", "-D", branch);
        }
        worktrees.remove(name);
    }

    /** 清理过期的临时 Worktree（委托 {@link StaleCleanup}）。 */
    public void pruneStale() {
        Instant cutoff = Instant.now().minusSeconds((long) staleCutoffHours * 3600L);
        StaleCleanup.cleanup(projectRoot, worktreeDir().toString(), cutoff);
    }

    // ───────────────────────── 快速恢复：纯文件系统读 HEAD ─────────────────────────

    /**
     * 读 Worktree 当前 HEAD 的 SHA，不启动 git 子进程。
     *
     * <p>链路：{@code .git} 指针文件（内容 {@code gitdir: <path>}）→ 解析 gitdir →
     * 读 {@code HEAD}（可能是 {@code ref: refs/heads/...} 或直接是 SHA）→ 若是 ref 再解析。
     * 失败时返回 {@code null}，调用方按「无基线」处理（fail-closed）。
     */
    static String readHead(String worktreePath) {
        try {
            Path dotGit = Path.of(worktreePath, ".git");
            if (!Files.isRegularFile(dotGit)) {
                return null;
            }
            String pointer = Files.readString(dotGit).strip();
            String prefix = "gitdir:";
            if (!pointer.startsWith(prefix)) {
                return null;
            }
            String rel = pointer.substring(prefix.length()).strip();
            Path gitDir = Path.of(rel).isAbsolute()
                    ? Path.of(rel)
                    : Path.of(worktreePath, rel).normalize();
            Path headFile = gitDir.resolve("HEAD");
            if (!Files.isRegularFile(headFile)) {
                return null;
            }
            String content = Files.readString(headFile).strip();
            if (content.startsWith("ref:")) {
                return resolveRef(gitDir, content.substring("ref:".length()).strip());
            }
            return SHA_PATTERN.matcher(content).matches() ? content : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String resolveRef(Path gitDir, String ref) {
        // worktree 的 gitdir 里常有 commondir 指向主仓库的 .git，本地 refs 实际放在主仓库。
        // 所以解析 ref 时要把「worktree 自身 gitdir」和「commondir 指向的主仓库」都试一遍。
        List<Path> roots = new ArrayList<>();
        roots.add(gitDir);
        Path commonDir = readCommonDir(gitDir);
        if (commonDir != null) {
            roots.add(commonDir);
        }
        // 第一遍：普通路径解析（refs/heads/xxx 之类的文件）
        for (Path root : roots) {
            Path refPath = Path.of(ref);
            Path resolved = refPath.isAbsolute() ? refPath : root.resolve(ref);
            try {
                if (Files.isRegularFile(resolved)) {
                    String s = Files.readString(resolved).strip();
                    if (SHA_PATTERN.matcher(s).matches()) {
                        return s;
                    }
                }
            } catch (IOException ignored) {
                // 继续尝试下一个根 / packed-refs
            }
        }
        // 回退到 packed-refs（worktree 的分支引用可能打包在主仓库里）
        for (Path root : roots) {
            Path packed = root.resolve("packed-refs");
            try {
                if (Files.isRegularFile(packed)) {
                    for (String line : Files.readAllLines(packed)) {
                        if (line.startsWith("#") || line.isBlank()) {
                            continue;
                        }
                        // 形如 "<sha> refs/heads/xxx"
                        String[] parts = line.split("\\s+");
                        if (parts.length == 2 && parts[1].equals(ref)) {
                            return parts[0];
                        }
                    }
                }
            } catch (IOException ignored) {
                // 忽略
            }
        }
        return null;
    }

    /** 读 gitdir 里的 {@code commondir} 指针（指向主仓库的 .git），不存在则返回 null。 */
    private static Path readCommonDir(Path gitDir) {
        try {
            Path commonFile = gitDir.resolve("commondir");
            if (!Files.isRegularFile(commonFile)) {
                return null;
            }
            String rel = Files.readString(commonFile).strip();
            return Path.of(rel).isAbsolute() ? Path.of(rel) : gitDir.resolve(rel).normalize();
        } catch (IOException e) {
            return null;
        }
    }

    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-f]{7,40}$");
}
