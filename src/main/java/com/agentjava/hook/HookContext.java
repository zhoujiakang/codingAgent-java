package com.agentjava.hook;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 事件上下文：一次 Hook 触发时，引擎把「刚刚发生了什么」打包成这个对象交给条件与动作。
 *
 * <p>它有两个职责：
 * <ol>
 *   <li><b>取字段</b>（{@link #getField}）：条件表达式里的 {@code tool} / {@code event} /
 *       {@code args.xxx} 都从这里取值。未知字段返回空串而不是报错——让条件安静地不匹配，
 *       配置才不会因为「某个变量在这个事件里不存在」就崩掉；</li>
 *   <li><b>替换变量</b>（{@link #expand}）：把 command / url / body / message 模板里的
 *       {@code $EVENT}、{@code $FILE_PATH} 之类换成实际值。</li>
 * </ol>
 */
public record HookContext(
        EventName event,
        String toolName,
        Map<String, Object> toolArgs,
        String filePath,
        String message,
        String error
) {

    /** 未定义的 {@code $TOOL_ARGS.xxx} 兜底成空串，不留占位符在命令里。 */
    private static final Pattern UNKNOWN_TOOL_ARG = Pattern.compile("\\$TOOL_ARGS\\.[A-Za-z0-9_]+");

    /** 只带事件的上下文：session_start、startup 这类没有工具信息的事件用它。 */
    public static HookContext of(EventName event) {
        return new HookContext(event, null, Map.of(), null, null, null);
    }

    /** 带一段说明文本的上下文：error、command_execute 等。 */
    public static HookContext of(EventName event, String message) {
        return new HookContext(event, null, Map.of(), null, message, null);
    }

    /** 工具事件的上下文：pre/post_tool_use、permission_request。 */
    public static HookContext ofTool(EventName event, String toolName, Map<String, Object> args) {
        return new HookContext(event, toolName, args == null ? Map.of() : args, null, null, null);
    }

    /** 文件事件的上下文：file_change。 */
    public static HookContext ofFile(EventName event, String filePath) {
        return new HookContext(event, null, Map.of(), filePath, null, null);
    }

    /**
     * 按字段名取值。
     *
     * <p>三类字段：{@code tool} 取工具名、{@code event} 取事件名、
     * {@code args.xxx} 取工具参数字典里的对应字段。另外 {@code file_path} /
     * {@code message} / {@code error} 也能直接取，方便条件里做「出错才触发」这类判断。
     */
    public String getField(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String field = name.strip();
        return switch (field) {
            case "tool" -> nz(toolName);
            case "event" -> event == null ? "" : event.value();
            case "file_path" -> nz(filePath);
            case "message" -> nz(message);
            case "error" -> nz(error);
            default -> {
                if (field.startsWith("args.") && toolArgs != null) {
                    Object v = toolArgs.get(field.substring("args.".length()));
                    yield v == null ? "" : String.valueOf(v);
                }
                // 未知字段返回空串：条件不匹配，但不报错
                yield "";
            }
        };
    }

    /**
     * 模板变量替换。
     *
     * <p>已知变量逐个替换，再遍历工具参数字典替换 {@code $TOOL_ARGS.xxx}。
     * 未定义的 {@code $TOOL_ARGS.xxx} 最后统一清空成空串。
     *
     * <p><b>为什么不把所有 {@code $XXX} 都清空</b>：command 执行器跑的是 shell，
     * 命令里的 {@code $HOME}、{@code $PATH} 是合法的 shell 变量，全清会误伤。
     * 所以只有 Hook 自己那套 {@code $TOOL_ARGS.*} 会被兜底清空。
     */
    public String expand(String template) {
        if (template == null || template.indexOf('$') < 0) {
            return template;
        }
        String result = template;
        result = result.replace("$EVENT", event == null ? "" : event.value());
        result = result.replace("$TOOL_NAME", nz(toolName));
        result = result.replace("$FILE_PATH", nz(filePath));
        result = result.replace("$MESSAGE", nz(message));
        result = result.replace("$ERROR", nz(error));
        if (toolArgs != null) {
            for (Map.Entry<String, Object> entry : toolArgs.entrySet()) {
                Object v = entry.getValue();
                result = result.replace("$TOOL_ARGS." + entry.getKey(), v == null ? "" : String.valueOf(v));
            }
        }
        return UNKNOWN_TOOL_ARG.matcher(result).replaceAll("");
    }

    /** 工具参数字典的不可变拷贝；空参数返回空 Map。 */
    public Map<String, Object> args() {
        return toolArgs == null ? Map.of() : new LinkedHashMap<>(toolArgs);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
