package com.agentjava.teams;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Tmux 后端：在独立的 tmux 窗口中启动 teammate。
 *
 * <p>窗格里的 teammate 是一个完整的独立进程，和 Lead 不在同一个 JVM 里，
 * 因此通信只能走文件邮箱。
 */
public final class TmuxBackend {

    private static final Logger log = Logger.getLogger(TmuxBackend.class.getName());

    private TmuxBackend() {}

    /**
     * 用 {@code tmux new-window -d -n} 创建一个后台窗口并命名。
     * 窗口名格式是 {@code {teamName}-{memberName}}，方便在 tmux 里识别。
     */
    public static String spawnTmuxTeammate(String teamName, String memberName, String cliCommand) throws Exception {
        String paneName = teamName + "-" + memberName;
        var pb = new ProcessBuilder("tmux", "new-window", "-d", "-n", paneName, cliCommand);
        pb.redirectErrorStream(true);
        var proc = pb.start();
        proc.getInputStream().readAllBytes();
        boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
        if (!finished || proc.exitValue() != 0) {
            throw new RuntimeException("Failed to spawn tmux window: " + paneName);
        }
        return paneName;
    }

    /** 停止队友：先 {@code send-keys C-c} 发中断信号，等一下再 {@code kill-window} 强制关闭。 */
    public static void stopTmuxTeammate(String paneName) {
        try {
            new ProcessBuilder("tmux", "send-keys", "-t", paneName, "C-c")
                    .start().waitFor(5, TimeUnit.SECONDS);
            Thread.sleep(200);
            new ProcessBuilder("tmux", "kill-window", "-t", paneName)
                    .start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.fine("Failed to stop tmux teammate: " + e.getMessage());
        }
    }
}
