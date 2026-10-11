package com.agentjava.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Worktree 创建后的四步初始化。
 *
 * <p>{@code git worktree add} 出来的目录是干净的，但缺主仓库里已有的若干运行时依赖。
 * 这四步<b>每一步都 best-effort</b>：用 try/catch 包裹，失败只记日志不抛异常——
 * 配置复制失败绝不能让整个 Worktree 创建中断。
 *
 * <ol>
 *   <li><b>复制本地配置</b>：{@code .agentjava/settings.local.json} 存密钥/环境变量，被 gitignore 忽略，
 *       worktree 不会自动拥有，要从主仓库拷过去；</li>
 *   <li><b>配置 git hooks</b>：优先 {@code .husky/}，回退 {@code .git/hooks/}，再 {@code git config core.hooksPath}；</li>
 *   <li><b>软链接大目录</b>：{@code node_modules}/{.venv}/{@code vendor} 等按配置软链到主仓库，省磁盘；
 *       带 {@code ..} 穿越检查，源不存在或目标已存在都跳过；</li>
 *   <li><b>复制 .worktreeinclude 文件</b>：被 gitignore 但 worktree 需要的文件（典型 {@code .env}），
 *       用 git 列出被忽略项、按 {@code .worktreeinclude} 的模式过滤后复制。</li>
 * </ol>
 */
public final class PostCreationSetup {

    /** 配置目录名（与 {@link WorktreeSessionStore#CONFIG_DIR} 保持一致）。 */
    private static final String CONFIG_DIR = WorktreeSessionStore.CONFIG_DIR;

    private PostCreationSetup() {
    }

    /** 执行全部四步初始化。任何一步失败都不抛出。 */
    public static void perform(String repoRoot, String worktreePath, List<String> symlinkDirs) {
        copySettingsLocal(repoRoot, worktreePath);
        configureHooksPath(repoRoot, worktreePath);
        symlinkDirectories(repoRoot, worktreePath, symlinkDirs == null ? List.of() : symlinkDirs);
        copyWorktreeIncludeFiles(repoRoot, worktreePath);
    }

    // ───────────────────────── A. 复制本地配置 ─────────────────────────

    private static void copySettingsLocal(String repoRoot, String worktreePath) {
        try {
            Path src = Path.of(repoRoot, CONFIG_DIR, "settings.local.json");
            if (!Files.isRegularFile(src)) {
                return;
            }
            Path dst = Path.of(worktreePath, CONFIG_DIR, "settings.local.json");
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // best-effort：忽略
        }
    }

    // ───────────────────────── B. 配置 git hooks ─────────────────────────

    private static void configureHooksPath(String repoRoot, String worktreePath) {
        try {
            Path hooksDir = null;
            Path husky = Path.of(repoRoot, ".husky");
            if (Files.isDirectory(husky)) {
                hooksDir = husky;
            } else {
                Path gitHooks = Path.of(repoRoot, ".git", "hooks");
                if (Files.isDirectory(gitHooks)) {
                    hooksDir = gitHooks;
                }
            }
            if (hooksDir == null) {
                return;
            }
            GitUtil.runQuiet(worktreePath, "config", "core.hooksPath",
                    hooksDir.toAbsolutePath().normalize().toString());
        } catch (RuntimeException ignored) {
            // best-effort：忽略
        }
    }

    // ───────────────────────── C. 软链接大目录 ─────────────────────────

    private static void symlinkDirectories(String repoRoot, String worktreePath, List<String> symlinkDirs) {
        Path wtNorm = Path.of(worktreePath).toAbsolutePath().normalize();
        for (String dir : symlinkDirs) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            // 拒绝含 .. 的源，防止穿越到仓库外
            if (dir.contains("..")) {
                continue;
            }
            try {
                Path source = Path.of(repoRoot, dir).toAbsolutePath().normalize();
                Path target = Path.of(worktreePath, dir).toAbsolutePath().normalize();
                // 目标必须仍落在 worktree 目录内
                if (!target.startsWith(wtNorm)) {
                    continue;
                }
                if (!Files.exists(source) || Files.exists(target)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.createSymbolicLink(target, source);
            } catch (IOException ignored) {
                // best-effort：忽略
            }
        }
    }

    // ───────────────────────── D. 复制 .worktreeinclude 文件 ─────────────────────────

    private static void copyWorktreeIncludeFiles(String repoRoot, String worktreePath) {
        try {
            Path includeFile = Path.of(repoRoot, ".worktreeinclude");
            if (!Files.isRegularFile(includeFile)) {
                return;
            }
            List<String> patterns = parseIncludePatterns(includeFile);
            if (patterns.isEmpty()) {
                return;
            }
            String listed = GitUtil.run(repoRoot, "ls-files", "--others",
                    "--ignored", "--exclude-standard", "--directory");
            if (listed == null || listed.isBlank()) {
                return;
            }
            for (String line : listed.split("\n")) {
                String rel = line.strip();
                if (rel.isEmpty()) {
                    continue;
                }
                if (rel.endsWith("/")) {
                    continue; // 目录不复制
                }
                if (!matchesAny(rel, patterns)) {
                    continue;
                }
                Path src = Path.of(repoRoot, rel);
                Path dst = Path.of(worktreePath, rel);
                if (!Files.isRegularFile(src)) {
                    continue;
                }
                try {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // 单个文件失败不影响其余
                }
            }
        } catch (IOException ignored) {
            // best-effort：忽略
        }
    }

    private static List<String> parseIncludePatterns(Path includeFile) throws IOException {
        var out = new ArrayList<String>();
        for (String raw : Files.readAllLines(includeFile)) {
            String p = raw.strip();
            if (p.isEmpty() || p.startsWith("#")) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    /** 匹配 .worktreeinclude 的三种写法：精确路径、basename、目录前缀（以 / 结尾）。 */
    private static boolean matchesAny(String relPath, List<String> patterns) {
        Path p = Path.of(relPath);
        for (String pattern : patterns) {
            if (pattern.endsWith("/")) {
                if (relPath.startsWith(pattern) || relPath.startsWith(pattern.substring(0, pattern.length() - 1) + "/")) {
                    return true;
                }
                continue;
            }
            if (pattern.equals(relPath)) {
                return true;
            }
            if (Path.of(pattern).getFileName().equals(p.getFileName())) {
                return true;
            }
        }
        return false;
    }
}
