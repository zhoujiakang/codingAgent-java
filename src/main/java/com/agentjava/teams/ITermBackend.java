package com.agentjava.teams;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * iTerm2 后端：通过 AppleScript 操控 iTerm2 标签页，在其中启动 teammate 进程。
 *
 * <p>和 Tmux 后端的差异只在执行媒介，模式一样：在独立终端环境中启动一个完整的
 * AgentJava 进程。
 */
public final class ITermBackend {

    private static final Logger log = Logger.getLogger(ITermBackend.class.getName());

    private ITermBackend() {}

    /** 新建一个 iTerm2 标签页，命名后把 CLI 命令写进去。 */
    public static String spawnITermTeammate(String teamName, String memberName, String cliCommand) throws Exception {
        String tabName = teamName + "-" + memberName;
        String escapedCmd = cliCommand.replace("\"", "\\\"");
        String script = """
                tell application "iTerm2"
                    tell current window
                        create tab with profile "Default"
                        tell current session of current tab
                            set name to "%s"
                            write text "%s"
                        end tell
                    end tell
                end tell
                """.formatted(tabName, escapedCmd);

        var pb = new ProcessBuilder("osascript", "-e", script);
        pb.redirectErrorStream(true);
        var proc = pb.start();
        proc.getInputStream().readAllBytes();
        boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
        if (!finished || proc.exitValue() != 0) {
            throw new RuntimeException("Failed to spawn iTerm2 tab: " + tabName);
        }
        return tabName;
    }

    /** 停止队友：按名字找到标签页并关闭。 */
    public static void stopITermTeammate(String tabName) {
        try {
            String script = """
                    tell application "iTerm2"
                        repeat with w in windows
                            repeat with t in tabs of w
                                if name of current session of t is "%s" then
                                    close t
                                    return
                                end if
                            end repeat
                        end repeat
                    end tell
                    """.formatted(tabName);
            new ProcessBuilder("osascript", "-e", script)
                    .start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.fine("Failed to stop iTerm2 teammate: " + e.getMessage());
        }
    }
}
