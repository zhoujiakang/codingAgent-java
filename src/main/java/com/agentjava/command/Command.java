package com.agentjava.command;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 命令定义：一条 Slash Command 的「身份证」。
 *
 * <p>把命令做成 record 是想让定义保持为纯数据（外加一个匹配方法），
 * 执行逻辑放在 {@link CommandHandler} 里。注册的时候定义与 handler 一并交给
 * {@link CommandRegistry}，两者不会被拆散。
 *
 * <p>几个字段的用途：
 * <ul>
 *   <li>{@code aliases}：别名。命中别名和命中主名等价，{@code /c} 就是 {@code /compact}；</li>
 *   <li>{@code usage}：用法示例，{@code /help <命令名>} 详情里展示；</li>
 *   <li>{@code argPrompt}：命令需要参数却没给时的提示语。比冷冰冰的「缺少参数」友好，
 *       也让用户不必回头翻文档；</li>
 *   <li>{@code hidden}：内部命令与逃生舱（如 {@code /exit}）设为 true，不出现在
 *       {@code /help} 列表和 Tab 补全里，但照常可以执行；</li>
 *   <li>{@code handler}：执行函数。{@link CommandType#LOCAL_UI} 命令可以为 null，
 *       表示这段多样状态切换只有 UI 层能做。</li>
 * </ul>
 *
 * <p>名字与别名在构造时统一小写化：解析器已经把用户输入转成了小写，
 * 这里保持一致，避免出现「注册了 Help 却永远匹配不上」的隐性问题。
 */
public record Command(String name,
                      String description,
                      List<String> aliases,
                      CommandType type,
                      boolean hidden,
                      String usage,
                      String argPrompt,
                      CommandHandler handler) {

    /** 名字的合法形态：小写字母（或 ?）开头，其余允许小写字母、数字、下划线、连字符。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z?][a-z0-9_-]*");

    /**
     * 最简形式：只要名字、描述、别名、类型、handler。
     * usage 默认为 {@code /<name>}，argPrompt 为空，命令可见。
     */
    public Command(String name, String description, String[] aliases,
                   CommandType type, CommandHandler handler) {
        this(name, description, aliases, type, false, "", "", handler);
    }

    /** 带用法与参数提示的形式。 */
    public Command(String name, String description, String[] aliases, CommandType type,
                   String usage, String argPrompt, CommandHandler handler) {
        this(name, description, aliases, type, false, usage, argPrompt, handler);
    }

    /** 完整形式（可以指定是否隐藏）。 */
    public Command(String name, String description, String[] aliases, CommandType type,
                   boolean hidden, String usage, String argPrompt, CommandHandler handler) {
        this(name, description, aliases == null ? List.of() : List.of(aliases),
                type, hidden, usage, argPrompt, handler);
    }

    /**
     * 规范化：去空格、转小写、校验形态，给缺省字段兜底。
     *
     * <p>故意在这里做严格校验而不是等到查找时才发现——注册期就把非法定义挡住，
     * 比运行时「命令神秘失踪」好排查得多。
     */
    public Command {
        Objects.requireNonNull(name, "命令名不能为 null");
        name = name.toLowerCase(Locale.ROOT).strip();
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("非法命令名: " + name);
        }
        if (description == null) {
            description = "";
        }
        List<String> normalizedAliases = aliases == null ? List.of() : aliases.stream()
                .map(a -> a.toLowerCase(Locale.ROOT).strip())
                .toList();
        for (String alias : normalizedAliases) {
            if (!NAME_PATTERN.matcher(alias).matches()) {
                throw new IllegalArgumentException("非法别名: " + alias);
            }
            if (alias.equals(name)) {
                throw new IllegalArgumentException("别名不能与命令名相同: " + alias);
            }
        }
        aliases = normalizedAliases;
        type = type == null ? CommandType.LOCAL : type;
        usage = usage == null || usage.isBlank() ? "/" + name : usage.strip();
        argPrompt = argPrompt == null ? "" : argPrompt.strip();
    }

    /** 精确匹配：命令名或任一别名命中即 true。调用方传进来的已经是小写。 */
    public boolean matches(String input) {
        if (name.equals(input)) {
            return true;
        }
        return aliases.contains(input);
    }

    /** 这条命令是否自带执行逻辑（{@code LOCAL_UI} 命令可以没有）。 */
    public boolean hasHandler() {
        return handler != null;
    }

    /** {@code /help} 列表里展示的别名串，如 {@code /help, /h, /?}。 */
    public String displayNames() {
        StringBuilder sb = new StringBuilder("/").append(name);
        for (String alias : aliases) {
            sb.append(", /").append(alias);
        }
        return sb.toString();
    }
}
