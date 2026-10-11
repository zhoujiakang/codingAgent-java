package com.agentjava.hook;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 单个（叶子）条件：{@code field operator value}。
 *
 * <p>四种操作符：
 * <ul>
 *   <li>{@code ==} 精确匹配；</li>
 *   <li>{@code !=} 反向匹配；</li>
 *   <li>{@code =~} 正则匹配，用 {@code find()} 做<b>部分</b>匹配，不要求整串命中；</li>
 *   <li>{@code ~=} glob 匹配，复用 {@link PathMatcher} 的 {@code glob:} 语法。</li>
 * </ul>
 *
 * <p>{@code =~} 与 {@code ~=} 只是左右反转，很容易记混。助记：等号在波浪号<b>前</b>的
 * {@code =~} 是正则（表达力强、张牙舞爪），波浪号在前的 {@code ~=} 是 glob（文件路径通配，
 * 更温和）。
 *
 * <p>正则与 glob 的匹配都做了 try-catch 兜底：用户把正则写错了，最多是条件不匹配，
 * 不会让引擎崩掉。
 */
public record Condition(String field, Operator op, String value) {

    /** 操作符。扫描顺序即优先级，{@code !=} 必须排在 {@code ==} 前面。 */
    public enum Operator {
        EQ("==", "精确匹配"),
        NE("!=", "反向匹配"),
        RE("=~", "正则匹配"),
        GLOB("~=", "glob 匹配");

        private final String token;
        private final String label;

        Operator(String token, String label) {
            this.token = token;
            this.label = label;
        }

        public String token() {
            return token;
        }

        public String label() {
            return label;
        }

        public static Optional<Operator> fromToken(String token) {
            for (Operator o : values()) {
                if (o.token.equals(token)) {
                    return Optional.of(o);
                }
            }
            return Optional.empty();
        }
    }

    /** 扫描顺序：{@code !=} 在 {@code ==} 之前，避免 {@code a != b} 被误判成等号比较。 */
    private static final String[] OPERATOR_TOKENS = {"!=", "=~", "~=", "=="};

    /**
     * 求值：从上下文取字段的实际值，再按操作符比对。
     *
     * <p>操作符为 null 表示「只写了字段名」的真值判断——字段非空即为真，
     * 比如 {@code if: tool} 就是「只要有工具名就触发」。
     */
    public boolean matches(HookContext ctx) {
        if (ctx == null) {
            return false;
        }
        String actual = ctx.getField(field());
        if (op() == null) {
            return !actual.isEmpty();
        }
        return switch (op()) {
            case EQ -> actual.equals(value());
            case NE -> !actual.equals(value());
            case RE -> regexMatch(actual, value());
            case GLOB -> globMatch(actual, value());
        };
    }

    /** 正则：{@code find()} 部分匹配；正则语法错误一律视为不匹配。 */
    private static boolean regexMatch(String actual, String pattern) {
        if (pattern == null) {
            return false;
        }
        try {
            return Pattern.compile(pattern).matcher(actual).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * glob：借用 {@code glob:} 语法的 {@link PathMatcher}。
     *
     * <p>注意 {@code *} 不跨 {@code /}，所以 {@code *.json} 匹配不上绝对路径
     * {@code /tmp/a/config.json}——要按后缀匹配请改用正则 {@code =~ /\.json$/}。
     */
    private static boolean globMatch(String actual, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            return matcher.matches(Paths.get(actual));
        } catch (RuntimeException e) {
            // 包含 InvalidPathException：路径非法、glob 语法错误都只当作不匹配
            return false;
        }
    }

    /**
     * 解析一个叶子条件。
     *
     * @throws IllegalArgumentException 字段缺失或操作符认不出
     */
    public static Condition parse(String expr) {
        String s = expr == null ? "" : expr.strip();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("条件表达式为空");
        }
        for (String token : OPERATOR_TOKENS) {
            int idx = s.indexOf(token);
            if (idx < 0) {
                continue;
            }
            String field = s.substring(0, idx).strip();
            if (field.isEmpty()) {
                throw new IllegalArgumentException("条件缺少字段：" + s);
            }
            String raw = s.substring(idx + token.length()).strip();
            Operator op = Operator.fromToken(token)
                    .orElseThrow(() -> new IllegalArgumentException("未知操作符：" + token));
            return new Condition(field, op, cleanValue(op, raw));
        }
        // 没有操作符：整段当字段名，非空即真
        return new Condition(s, null, null);
    }

    /**
     * 清洗右值：去掉成对引号；正则的 {@code /.../} 是分隔符而非模式的一部分，也要剥掉。
     *
     * <p>这条很关键：配置里写 {@code =~ /\.json$/}，带着斜杠去 compile 得到的是
     * 「一个字面 / + .json + 行尾 + 一个字面 /」，永远匹配不上任何东西。
     */
    private static String cleanValue(Operator op, String raw) {
        String v = raw;
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                v = v.substring(1, v.length() - 1);
            } else if (op == Operator.RE && first == '/' && last == '/') {
                v = v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    /** 还原成人类可读的表达式，用于 {@code /hooks} 展示。 */
    public String display() {
        if (op() == null) {
            return field();
        }
        return field() + " " + op().token() + " " + showValue();
    }

    /** 正则按书写习惯还原成 {@code /.../}，其余类型加引号。 */
    private String showValue() {
        if (value() == null) {
            return "";
        }
        return op() == Operator.RE ? "/" + value() + "/" : "\"" + value() + "\"";
    }
}
