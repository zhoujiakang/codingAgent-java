package com.agentjava.hook;

import java.util.ArrayList;
import java.util.List;

/**
 * 条件组：一个或多个叶子条件用同一种组合符串起来。
 *
 * <p><b>{@code &&} 和 {@code ||} 不能混用</b>：一旦允许混用就要处理运算符优先级和括号，
 * 解析器的复杂度会飙升。Hook 条件不是编程语言，简单组合够用；真需要复杂逻辑就拆成多个 Hook。
 * 混用在解析阶段直接报错，配置加载期就能暴露，不会等到运行时才发现条件没生效。
 *
 * <p>扫描分隔符时会跳过引号内与正则 {@code /.../} 内的内容，这样
 * {@code args.command =~ /rm\s+-rf/} 里的字符不会被误当成组合符。
 */
public record ConditionGroup(Combinator combinator, List<Condition> conditions) {

    public enum Combinator {
        AND("&&", "全部满足"),
        OR("||", "任一满足");

        private final String token;
        private final String label;

        Combinator(String token, String label) {
            this.token = token;
            this.label = label;
        }

        public String token() {
            return token;
        }

        public String label() {
            return label;
        }
    }

    /** 无条件：{@code if} 省略时的取值，恒真。 */
    public static final ConditionGroup ALWAYS = new ConditionGroup(Combinator.AND, List.of());

    public boolean isEmpty() {
        return conditions == null || conditions.isEmpty();
    }

    public boolean matches(HookContext ctx) {
        if (isEmpty()) {
            return true;
        }
        if (combinator == Combinator.OR) {
            for (Condition c : conditions) {
                if (c.matches(ctx)) {
                    return true;
                }
            }
            return false;
        }
        for (Condition c : conditions) {
            if (!c.matches(ctx)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析条件表达式。
     *
     * @param expr YAML 里 {@code if:} 后面的字符串；null 或空白表示无条件
     * @throws IllegalArgumentException 混用了 {@code &&} 与 {@code ||}，或子条件不合法
     */
    public static ConditionGroup parse(String expr) {
        String s = expr == null ? "" : expr.strip();
        if (s.isEmpty()) {
            return ALWAYS;
        }
        List<String> parts = splitTopLevel(s);
        if (parts.size() == 1) {
            Condition only = Condition.parse(parts.get(0));
            return new ConditionGroup(Combinator.AND, List.of(only));
        }

        // 回头确认所有分隔符是同一种：混用直接拒绝
        List<String> ops = collectOperators(s);
        boolean hasAnd = ops.contains("&&");
        boolean hasOr = ops.contains("||");
        if (hasAnd && hasOr) {
            throw new IllegalArgumentException(
                    "条件表达式不能混用 && 和 ||：" + s + "（拆成多条 Hook 表达更清晰）");
        }
        Combinator combinator = hasOr ? Combinator.OR : Combinator.AND;
        List<Condition> conditions = new ArrayList<>();
        for (String part : parts) {
            conditions.add(Condition.parse(part));
        }
        return new ConditionGroup(combinator, conditions);
    }

    /** 按顶层分隔符切段；引号内与正则 /.../ 内的 {@code &} {@code |} 不参与切分。 */
    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean inRegex = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                cur.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (inRegex) {
                cur.append(ch);
                if (ch == '/') {
                    inRegex = false;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                cur.append(ch);
                continue;
            }
            if (ch == '/') {
                inRegex = true;
                cur.append(ch);
                continue;
            }
            if (ch == '&' && i + 1 < s.length() && s.charAt(i + 1) == '&') {
                parts.add(cur.toString());
                cur.setLength(0);
                i++;
                continue;
            }
            if (ch == '|' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
                parts.add(cur.toString());
                cur.setLength(0);
                i++;
                continue;
            }
            cur.append(ch);
        }
        parts.add(cur.toString());
        return parts;
    }

    /** 收集顶层出现的分隔符，用于检测混用。 */
    private static List<String> collectOperators(String s) {
        List<String> ops = new ArrayList<>();
        char quote = 0;
        boolean inRegex = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (inRegex) {
                if (ch == '/') {
                    inRegex = false;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                continue;
            }
            if (ch == '/') {
                inRegex = true;
                continue;
            }
            if (ch == '&' && i + 1 < s.length() && s.charAt(i + 1) == '&') {
                ops.add("&&");
                i++;
            } else if (ch == '|' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
                ops.add("||");
                i++;
            }
        }
        return ops;
    }

    /** 还原成人类可读的表达式，用于 {@code /hooks} 展示。 */
    public String display() {
        if (isEmpty()) {
            return "（无条件）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sb.append(' ').append(combinator.token()).append(' ');
            }
            sb.append(conditions.get(i).display());
        }
        return sb.toString();
    }
}
