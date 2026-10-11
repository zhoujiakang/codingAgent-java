package com.agentjava.worktree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Git 命令执行助手（Worktree 模块内部使用）。
 *
 * <p>所有调用统一注入 {@code GIT_TERMINAL_PROMPT=0} 与 {@code GIT_ASKPASS=''}：
 * 万一某条命令需要凭证，进程不会挂起等输入——它会直接失败，失败由上层按
 * fail-closed 语义处理（宁可多保留一个目录也不误删）。
 *
 * <p>两条入口：
 * <ul>
 *   <li>{@link #run(String, String...)}：返回合并后的 stdout（去掉首尾空白），
 *       失败时返回 {@code null}；</li>
 *   <li>{@link #runQuiet(String, String...)}：只关心成不成功，stdout 直接丢弃。</li>
 * </ul>
 */
public final class GitUtil {

    private GitUtil() {
    }

    /** 在 {@code cwd} 目录下执行 git 命令，返回 stdout；任一异常都返回 {@code null}。 */
    public static String run(String cwd, String... args) {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new java.io.File(cwd));
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "");
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes());
            }
            boolean ok = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                return null;
            }
            return out.strip();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** 只关心成功与否；stdout 丢弃。 */
    public static boolean runQuiet(String cwd, String... args) {
        return run(cwd, args) != null;
    }
}
