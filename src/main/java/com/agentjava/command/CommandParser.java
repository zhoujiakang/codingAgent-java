package com.agentjava.command;

import java.util.Locale;

/**
 * 命令解析器：把一行输入拆成「命令名 + 参数」。
 *
 * <p>规则很朴素：
 * <ol>
 *   <li>去掉首尾空白，不是 {@code /} 开头就不是命令（交给 Agent）；</li>
 *   <li>去掉开头的 {@code /}；</li>
 *   <li>按<b>第一个</b>空格切开，左边是命令名、右边是参数。参数不再切分，
 *       完整留给 handler——要不要再拆是命令自己的事；</li>
 *   <li>命令名转小写（{@code /Help} 与 {@code /help} 等价）。</li>
 * </ol>
 *
 * <p>只输入一个 {@code /} 时命令名为空串：这不是错误，而是「我想看看有哪些命令」，
 * 调用方据此直接列出命令清单，比报错友好。
 */
public final class CommandParser {

    private CommandParser() {
    }

    /** 解析结果。{@code isCommand} 为 false 时 name 与 args 均无意义。 */
    public record ParsedInput(String name, String args, boolean isCommand) {

        /** 非命令输入的统一形态。 */
        public static ParsedInput notCommand() {
            return new ParsedInput("", "", false);
        }
    }

    /** 解析一行输入；永远不为 null。 */
    public static ParsedInput parse(String input) {
        if (input == null) {
            return ParsedInput.notCommand();
        }
        String trimmed = input.strip();
        if (!trimmed.startsWith("/")) {
            return ParsedInput.notCommand();
        }
        String body = trimmed.substring(1);
        int space = body.indexOf(' ');
        if (space < 0) {
            return new ParsedInput(body.toLowerCase(Locale.ROOT), "", true);
        }
        String name = body.substring(0, space).toLowerCase(Locale.ROOT);
        String args = body.substring(space + 1).strip();
        return new ParsedInput(name, args, true);
    }

    /** 快速判断：这行输入要不要走命令系统。 */
    public static boolean isCommand(String input) {
        return input != null && input.strip().startsWith("/");
    }
}
