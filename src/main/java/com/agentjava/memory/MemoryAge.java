package com.agentjava.memory;

/**
 * 记忆新鲜度计算。
 *
 * <p>自动记忆是一批「某一时刻的观察」，不是系统的实时状态。放了半个月的记忆里写着
 * "配置解析在 ConfigLoader.java:42"，而那行代码早就被挪走了——如果模型照着它断言，
 * 就会理直气壮地给出一个错答案。过期保护做的事很简单：超过一天的记忆，
 * 在正文前面附一段提醒，让它用之前先验证。
 */
public final class MemoryAge {

    private static final long DAY_MS = 86_400_000L;

    private MemoryAge() {
    }

    /** 按毫秒差算天数。 */
    public static int ageDays(long mtimeMs) {
        long diff = System.currentTimeMillis() - mtimeMs;
        if (diff <= 0) {
            return 0;
        }
        return (int) (diff / DAY_MS);
    }

    /** 转成人类可读文本：today / yesterday / N days ago。 */
    public static String age(long mtimeMs) {
        int d = ageDays(mtimeMs);
        if (d <= 0) {
            return "today";
        }
        if (d == 1) {
            return "yesterday";
        }
        return d + " days ago";
    }

    /** 超过 1 天的记忆附加一段验证提醒；1 天以内返回空串（不打扰）。 */
    public static String freshnessText(long mtimeMs) {
        int d = ageDays(mtimeMs);
        if (d <= 1) {
            return "";
        }
        return "This memory is " + d + " days old. "
                + "Memories are point-in-time observations, not live state — "
                + "claims about code behavior or file:line citations "
                + "may be outdated. Verify against current code before "
                + "asserting as fact.";
    }
}
