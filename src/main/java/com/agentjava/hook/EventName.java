package com.agentjava.hook;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 生命周期事件：Hook 的触发时机。
 *
 * <p>15 个事件按层级分成五组：
 * <ul>
 *   <li><b>会话级</b>：{@link #SESSION_START} / {@link #SESSION_END}，一个会话各一次；</li>
 *   <li><b>轮次级</b>：{@link #TURN_START} / {@link #TURN_END}，用户发消息与 Agent 收尾；</li>
 *   <li><b>工具级</b>：{@link #PRE_TOOL_USE} / {@link #POST_TOOL_USE}，日常用得最多的一组；</li>
 *   <li><b>消息级</b>：{@link #PRE_SEND} / {@link #POST_RECEIVE}，请求发出前与响应收到后；</li>
 *   <li><b>系统与场景级</b>：{@link #STARTUP} / {@link #SHUTDOWN} / {@link #ERROR} /
 *       {@link #COMPACT} / {@link #PERMISSION_REQUEST} / {@link #FILE_CHANGE} /
 *       {@link #COMMAND_EXECUTE}。</li>
 * </ul>
 *
 * <p><b>{@link #PRE_TOOL_USE} 是唯一能拦截的事件</b>：只有发生在工具执行之前，
 * 「拒绝」才有意义。其余事件都是通知型的，Hook 跑完 Agent 继续跑。
 */
public enum EventName {

    SESSION_START("session_start", "会话级", "新会话开始"),
    SESSION_END("session_end", "会话级", "会话结束"),
    TURN_START("turn_start", "轮次级", "用户发送新消息，一轮开始"),
    TURN_END("turn_end", "轮次级", "Agent 完成回复，一轮结束"),
    PRE_TOOL_USE("pre_tool_use", "工具级", "工具执行之前（唯一可拦截）"),
    POST_TOOL_USE("post_tool_use", "工具级", "工具执行之后"),
    PRE_SEND("pre_send", "消息级", "消息发送给 LLM 之前"),
    POST_RECEIVE("post_receive", "消息级", "收到 LLM 响应之后"),
    STARTUP("startup", "系统级", "程序启动"),
    SHUTDOWN("shutdown", "系统级", "程序退出"),
    ERROR("error", "系统级", "发生错误"),
    COMPACT("compact", "系统级", "上下文压缩"),
    PERMISSION_REQUEST("permission_request", "场景级", "权限审批请求"),
    FILE_CHANGE("file_change", "场景级", "文件被修改"),
    COMMAND_EXECUTE("command_execute", "场景级", "Slash Command 执行");

    private final String value;
    private final String category;
    private final String description;

    EventName(String value, String category, String description) {
        this.value = value;
        this.category = category;
        this.description = description;
    }

    /** YAML 里写的名字，也是条件表达式里 {@code event} 字段的取值。 */
    public String value() {
        return value;
    }

    /** 所属层级，仅用于 {@code /hooks} 列表的分组展示。 */
    public String category() {
        return category;
    }

    public String description() {
        return description;
    }

    /** 只有 pre_tool_use 能拦截工具调用——配置校验据此拒绝其它事件上的 reject。 */
    public boolean isIntercepting() {
        return this == PRE_TOOL_USE;
    }

    /** 按 YAML 里写的名字反查；大小写与首尾空白都容忍，认不出返回 empty。 */
    public static Optional<EventName> fromValue(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        for (EventName e : values()) {
            if (e.value.equals(s)) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** 全部合法事件名，用于校验错误提示里给出可选值。 */
    public static String allValues() {
        return String.join(" / ", Arrays.stream(values()).map(EventName::value).toArray(String[]::new));
    }
}
