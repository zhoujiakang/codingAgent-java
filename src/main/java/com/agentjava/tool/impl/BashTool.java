package com.agentjava.tool.impl;

import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolCategory;
import com.agentjava.tool.ToolResult;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 执行 shell 命令，合并 stdout/stderr 返回。
 *
 * <p>ProcessBuilder 起子进程，waitFor(timeout) 控制超时，超时强杀。
 * 对 grep/diff/find/test 等命令，exit code 1 不算错误（没匹配到/有差异），
 * 只有 >= 2 才标记 isError。命令在 JVM 当前工作目录（即用户启动 agent 的项目目录）下执行。
 */
public class BashTool implements Tool {

    private static final int MAX_TIMEOUT = 600;

    // 特殊命令 exit code 1 的语义提示（grep 没匹配到、diff 文件有差异等）
    private static final Map<String, String> EXIT_ONE_HINTS = Map.of(
            "grep", "no matches found",
            "egrep", "no matches found",
            "fgrep", "no matches found",
            "rg", "no matches found",
            "diff", "files differ",
            "find", "some directories were inaccessible",
            "test", "condition is false",
            "[", "condition is false"
    );

    private static final String DESCRIPTION = """
            Execute a shell command and return stdout and stderr.

            IMPORTANT: Avoid using this tool to run cat, head, tail, sed, awk, or echo commands. \
            Instead use the dedicated ReadFile, EditFile, or WriteFile tools which provide a better experience.

            Usage notes:
            - The working directory is the project you launched the agent from; avoid cd unless the user explicitly requests it.
            - Always quote file paths containing spaces with double quotes.
            - Optional timeout in seconds (max 600). Default is 120s.
            - Use && to chain sequential dependent commands. Use ; only when you don't care if earlier commands fail.
            - DO NOT use newlines to separate commands.

            Git Safety Protocol:
            - NEVER run destructive git commands (push --force, reset --hard, checkout ., clean -f, branch -D) unless the user explicitly requests it.
            - NEVER skip hooks (--no-verify) unless the user explicitly requests it.
            - Prefer creating a new commit rather than amending an existing one.""";

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of("type", "string", "description", "Shell command to execute"),
                                "timeout", Map.of("type", "integer", "description", "Timeout in seconds (max 600)", "default", 120)
                        ),
                        "required", List.of("command")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String command = stringArg(args, "command", "");
        if (command.isEmpty()) {
            return ToolResult.error("Error: command is required");
        }

        int timeout = intArg(args, "timeout", 120);
        if (timeout > MAX_TIMEOUT) {
            timeout = MAX_TIMEOUT;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            // 合并 stdout 和 stderr 到同一个流，简化输出解析
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 合并流后只需读取 getInputStream()
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes());
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Error: command timed out after " + timeout + "s");
            }

            int exitCode = process.exitValue();

            var sb = new StringBuilder();
            if (!output.isEmpty()) {
                sb.append(output);
                if (!output.endsWith("\n")) {
                    sb.append('\n');
                }
            }

            // 非零 exit code 时附加退出码信息，但不设置 isError（仅超时/中断才为 true）
            if (exitCode != 0) {
                sb.append("Exit code ").append(exitCode);
                String hint = getExitCodeHint(command, exitCode);
                if (hint != null) {
                    sb.append(" (").append(hint).append(")");
                }
                sb.append('\n');
            }

            return new ToolResult(sb.toString(), false);

        } catch (IOException e) {
            return ToolResult.error("Error executing command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Error: command interrupted");
        }
    }

    /**
     * 对特殊命令的 exit code 1 返回语义提示。
     * 管道命令取最后一段（bash 默认返回最后一个命令的 exit code）。
     */
    private String getExitCodeHint(String command, int exitCode) {
        if (exitCode != 1) {
            return null;
        }
        String baseCmd = extractBaseCommand(command);
        return EXIT_ONE_HINTS.get(baseCmd);
    }

    /**
     * 从完整命令字符串中提取基础命令名，处理管道（取最后一段）、env 前缀、路径前缀。
     */
    private String extractBaseCommand(String command) {
        String cmd = command.strip();

        int pipeIdx = cmd.lastIndexOf('|');
        if (pipeIdx >= 0 && pipeIdx < cmd.length() - 1) {
            cmd = cmd.substring(pipeIdx + 1).strip();
        }

        while (cmd.contains("=") && !cmd.startsWith("=")) {
            int spaceIdx = cmd.indexOf(' ');
            int eqIdx = cmd.indexOf('=');
            if (eqIdx < spaceIdx || spaceIdx == -1) {
                if (spaceIdx == -1) {
                    break;
                }
                cmd = cmd.substring(spaceIdx + 1).strip();
            } else {
                break;
            }
        }

        String[] parts = cmd.split("\\s+", 2);
        String token = parts[0];

        int slashIdx = token.lastIndexOf('/');
        if (slashIdx >= 0 && slashIdx < token.length() - 1) {
            token = token.substring(slashIdx + 1);
        }

        return token;
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        var v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return def;
    }
}
