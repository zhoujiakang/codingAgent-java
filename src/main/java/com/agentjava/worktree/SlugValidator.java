package com.agentjava.worktree;

import java.util.regex.Pattern;

/**
 * Slug 安全校验。
 *
 * <p>Worktree 名称会被当成<b>目录名</b>和<b>git 分支名</b>用。LLM 生成的输入不可信，
 * 不校验就可能拼出 {@code ../../etc/passwd} 这种路径穿越。这里是防御的第一道、也是最关键的一道门。
 *
 * <p>规则：总长度不超过 {@link #MAX_LENGTH}；允许小写/大写字母、数字、点、连字符、下划线；
 * 允许 {@code /} 作为嵌套分隔符（如 {@code team-refactor/alice}），但按 {@code /} 分段后
 * 每段单独校验，且禁止 {@code .} 与 {@code ..} 段。
 *
 * <p>{@code /} 不能直接进分支名（会被当成目录分隔），所以用 {@link #flatten} 换成 {@code +}。
 */
public final class SlugValidator {

    /** 名称长度上限（字符）。 */
    public static final int MAX_LENGTH = 64;

    /** 单段允许的字符：字母、数字、点、连字符、下划线。 */
    private static final Pattern VALID_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private SlugValidator() {
    }

    /**
     * 校验 slug，非法时抛 {@link IllegalArgumentException}。
     *
     * <p>用 {@code split("/", -1)} 的 {@code -1} 保留尾部空段：{@code foo/} 会多出一个空段，
     * 被正则拒绝——这正是我们想要的（不允许以斜杠结尾）。
     */
    public static void validate(String slug) {
        if (slug == null || slug.isEmpty()) {
            throw new IllegalArgumentException("slug 不能为空");
        }
        if (slug.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("slug 长度超过上限 " + MAX_LENGTH + "：" + slug);
        }
        for (String segment : slug.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("slug 不允许出现空段（多余斜杠）：" + slug);
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("slug 不允许出现 . 或 .. 段：" + slug);
            }
            if (!VALID_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("slug 含非法字符（只允许字母数字 . _ - 和 / 分隔）：" + slug);
            }
        }
    }

    /** 嵌套 slug 的 {@code /} 换成 {@code +}，供目录名与分支名使用。 */
    public static String flatten(String slug) {
        return slug.replace('/', '+');
    }

    /** Worktree 分支名前缀固定为 {@code worktree-}。 */
    public static String branchName(String slug) {
        return "worktree-" + flatten(slug);
    }
}
