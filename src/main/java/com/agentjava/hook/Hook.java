package com.agentjava.hook;

/**
 * 一条 Hook 规则：事件 + 条件 + 动作，外加三个执行控制开关。
 *
 * <p>三要素的含义就是「什么时候 / 什么情况下 / 做什么」。
 * 条件可以省（{@link ConditionGroup#ALWAYS} 表示无条件），事件和动作必须有。
 *
 * <p><b>为什么不是 record</b>：{@code once} 需要一个运行期可写的「已执行」标记
 * （{@link #shouldRun} / {@link #markExecuted}），record 的组件是不可变的，装不下这个状态。
 * 引擎里当然也可以额外维护一个 {@code fired} 集合，但那样「这条 Hook 跑过没有」
 * 就不在 Hook 自己身上了——放在这里更符合职责模型。
 *
 * <p>标记刻意<b>不做持久化</b>：{@code once} 的语义是「本次会话只触发一次」，
 * 重启程序等同开了个全新会话，{@code session_start} 这类 Hook 才能重新生效。
 */
public final class Hook {

    private final String id;
    private final EventName event;
    private final ConditionGroup condition;
    private final Action action;
    private final boolean reject;
    private final boolean once;
    private final boolean async;
    private final String onError;
    private final String source;

    private volatile boolean executed;

    public Hook(String id, EventName event, ConditionGroup condition, Action action,
                boolean reject, boolean once, boolean async, String onError) {
        this(id, event, condition, action, reject, once, async, onError, "");
    }

    public Hook(String id, EventName event, ConditionGroup condition, Action action,
                boolean reject, boolean once, boolean async, String onError, String source) {
        this.id = id;
        this.event = event;
        this.condition = condition == null ? ConditionGroup.ALWAYS : condition;
        this.action = action;
        this.reject = reject;
        this.once = once;
        this.async = async;
        this.onError = onError;
        this.source = source == null ? "" : source;
    }

    public String id() {
        return id;
    }

    public EventName event() {
        return event;
    }

    public ConditionGroup condition() {
        return condition;
    }

    public Action action() {
        return action;
    }

    /** 是否拦截工具调用；只有 {@link EventName#PRE_TOOL_USE} 上允许为 true。 */
    public boolean reject() {
        return reject;
    }

    public boolean once() {
        return once;
    }

    public boolean async() {
        return async;
    }

    /** 动作执行失败时的策略：{@code ignore}（静默跳过）或 {@code reject}（视为拦截）。 */
    public String onError() {
        return onError;
    }

    /** 配置来源（项目级 / 用户级 / 本地级），只用于展示。 */
    public String source() {
        return source;
    }

    /** {@code once && executed} 同时为真就该跳过。 */
    public boolean shouldRun() {
        return !(once && executed);
    }

    public void markExecuted() {
        executed = true;
    }

    public boolean isExecuted() {
        return executed;
    }

    /** 开新会话时重置——{@code once} 的语义是「本次会话一次」，不是「程序生命期一次」。 */
    public void reset() {
        executed = false;
    }

    /** 条件是否成立；无条件恒真。 */
    public boolean matches(HookContext ctx) {
        return condition.matches(ctx);
    }

    /** 展示用的名字：有 id 用 id，没有就退回事件名。 */
    public String label() {
        return (id == null || id.isBlank()) ? (event == null ? "?" : event.value()) : id;
    }

    @Override
    public String toString() {
        return "Hook{" + label() + ", event=" + (event == null ? "?" : event.value())
                + ", if=" + condition.display() + "}";
    }
}
