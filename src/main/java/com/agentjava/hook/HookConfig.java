package com.agentjava.hook;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hook 的 YAML 配置加载：把 {@code config.yaml} 里的 {@code hooks} 列表映射成 {@link Hook}。
 *
 * <p><b>三处配置，追加合并</b>——这一点和 Skill 的「找到一个就停」完全不同：
 * 用户级和项目级声明的 Hook 会同时生效、叠加起来用。
 * <ol>
 *   <li>项目级 {@code .agentjava/config.yaml}：跟着项目走、随仓库一起被 review；</li>
 *   <li>用户级 {@code ~/.agentjava/config.yaml}：跨项目共享；</li>
 *   <li>本地级 {@code .agentjava/config.local.yaml}：不被 git 追踪，放本地临时改动。</li>
 * </ol>
 *
 * <p>加载顺序即执行顺序：声明靠前的 Hook 先跑，{@code pre_tool_use} 上第一个命中的
 * reject 会短路后面的，所以兜底拦截规则要往前放。
 *
 * <p>单条 Hook 解析失败只记错误、跳过这一条，不让整个启动崩掉——
 * 配置文件是人手写的，一处笔误不该让 Agent 起不来。
 */
public final class HookConfig {

    /** 项目级配置：相对工作目录。 */
    public static final String PROJECT_PATH = ".agentjava/config.yaml";

    /** 本地级配置：不被 git 追踪，放本地临时改动。 */
    public static final String LOCAL_PATH = ".agentjava/config.local.yaml";

    private HookConfig() {
    }

    /** 加载结果：解析成功的 Hook + 解析过程中攒下的问题（两者都可能是空的）。 */
    public record LoadResult(List<Hook> hooks, List<String> errors) {
    }

    /** 用户级配置的绝对路径；每次现算，方便测试期改 {@code user.home}。 */
    public static String globalPath() {
        return System.getProperty("user.home") + "/.agentjava/config.yaml";
    }

    /**
     * 按「项目级 → 用户级 → 本地级」的顺序加载并追加合并。
     *
     * @param workDir 工作目录；null 时退回 JVM 的 user.dir
     */
    public static LoadResult load(String workDir) {
        String wd = (workDir == null || workDir.isBlank())
                ? System.getProperty("user.dir") : workDir;
        List<Hook> all = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        loadInto(all, errors, Path.of(wd, PROJECT_PATH), "项目级");
        loadInto(all, errors, Path.of(globalPath()), "用户级");
        loadInto(all, errors, Path.of(wd, LOCAL_PATH), "本地级");
        return new LoadResult(all, errors);
    }

    /** 从单一文件加载，只用于测试与排错。 */
    public static LoadResult loadFrom(Path file, String source) {
        List<Hook> hooks = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (file != null) {
            loadInto(hooks, errors, file, source == null ? "" : source);
        }
        return new LoadResult(hooks, errors);
    }

    @SuppressWarnings("unchecked")
    private static void loadInto(List<Hook> out, List<String> errors, Path file, String source) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            errors.add(file + "：读取失败（" + e.getMessage() + "）");
            return;
        }
        Map<String, Object> raw;
        try {
            raw = new Yaml().load(text);
        } catch (Exception e) {
            errors.add(file + "：不是合法的 YAML（" + e.getMessage() + "）");
            return;
        }
        if (raw == null) {
            return;
        }
        Object node = raw.get("hooks");
        if (node == null) {
            return;
        }
        if (!(node instanceof List<?> list)) {
            errors.add(file + "：hooks 必须是一个列表");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            String where = file + " hooks[" + i + "]";
            try {
                if (!(item instanceof Map<?, ?> map)) {
                    errors.add(where + "：必须是一个对象（含 event / action 字段）");
                    continue;
                }
                out.add(parseHook((Map<String, Object>) map, source));
            } catch (RuntimeException e) {
                errors.add(where + "：" + e.getMessage());
            }
        }
    }

    /**
     * 解析一条 Hook。
     *
     * @throws IllegalArgumentException 事件名不合法、action 类型不合法、条件表达式不合法
     */
    public static Hook parseHook(Map<String, Object> node, String source) {
        String eventRaw = str(node.get("event"));
        EventName event = EventName.fromValue(eventRaw)
                .orElseThrow(() -> new IllegalArgumentException(
                        "event 不合法：'" + eventRaw + "'，可选值为 " + EventName.allValues()));

        ConditionGroup condition;
        try {
            condition = ConditionGroup.parse(str(node.get("if")));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("if 条件不合法：" + e.getMessage());
        }

        Action action = parseAction(node.get("action"));

        return new Hook(
                str(node.get("id")),
                event,
                condition,
                action,
                bool(node.get("reject")),
                bool(node.get("once")),
                bool(node.get("async")),
                onError(node),
                source == null ? "" : source);
    }

    @SuppressWarnings("unchecked")
    private static Action parseAction(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) map;
        String typeRaw = str(m.get("type"));
        ActionType type = ActionType.fromValue(typeRaw).orElse(null);
        Map<String, String> headers = null;
        Object headerNode = m.get("headers");
        if (headerNode instanceof Map<?, ?> hm) {
            headers = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : hm.entrySet()) {
                headers.put(String.valueOf(e.getKey()), str(e.getValue()));
            }
        }
        return new Action(
                type,
                str(m.get("command")),
                str(m.get("message")),
                str(m.get("url")),
                str(m.get("method")),
                headers,
                str(m.get("body")),
                duration(m.get("timeout")));
    }

    /** {@code on_error} 与 {@code onError} 都认：项目里其它配置段用下划线风格，这里一并兼容。 */
    private static String onError(Map<String, Object> node) {
        String v = str(node.get("on_error"));
        return v == null ? str(node.get("onError")) : v;
    }

    /** timeout：数字按秒处理，字符串支持 {@code 30s} / {@code 500ms} / ISO-8601。 */
    private static Duration duration(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            long millis = (long) (n.doubleValue() * 1000);
            return Duration.ofMillis(Math.max(0, millis));
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(s));
        } catch (NumberFormatException ignored) {
            // 不是纯数字，继续按带单位的写法解析
        }
        String lower = s.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(lower.substring(0, lower.length() - 2)));
            }
            if (lower.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(lower.substring(0, lower.length() - 1)));
            }
            if (lower.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(lower.substring(0, lower.length() - 1)));
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        try {
            return Duration.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 把 YAML 标量安全地转成字符串；null 或空白一律返回 null，交给校验去点名。 */
    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean bool(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }
}
