package com.agentjava.subagent;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 定义加载器：多来源扫描 + YAML frontmatter 解析。
 *
 * <p><b>四级来源，后者覆盖前者</b>（优先级从高到低反过来记更容易出错，这里按加载顺序写）：
 * <pre>
 *   1. 插件级  {pluginDir}/agents/     ← 优先级最低
 *   2. 内置级  AgentDefinition.builtins()
 *   3. 用户级  ~/.agentjava/agents/
 *   4. 项目级  {projectRoot}/.agentjava/agents/   ← 优先级最高
 * </pre>
 * 加载顺序就是覆盖顺序，同名定义后加载的赢。这条链保证「离使用场景越近的定义越有话语权」：
 * 项目级可以覆盖内置级，用户级可以覆盖插件级，但用户级覆盖不了项目级。
 *
 * <p><b>插件级为什么优先级最低</b>：防止第三方插件覆盖用户自己的定义或系统内置定义。
 * 本项目尚无插件系统，目录由调用方传入（传空列表即只有三级）。
 *
 * <p><b>为什么用 LinkedHashMap</b>：{@code put} 是覆盖语义，同名后来者赢，优先级天然成立；
 * 同时保留插入顺序，{@code subagent_type} 的 enum 列表顺序稳定——工具 schema 每次渲染出来
 * 都是同一份，Prompt Cache 的前缀不会被无谓地打破。
 *
 * <p><b>坏文件单独跳过</b>：解析失败只丢弃那一个文件，并把原因攒进
 * {@link LoadResult#errors}，其余定义照常加载。定义文件是人手写的 Markdown，
 * 一处笔误不该让整个子 Agent 系统失效。
 */
public final class AgentLoader {

    /** 配置目录名（沿用 {@code .agentjava}）。 */
    public static final String CONFIG_DIR = ".agentjava";

    /** 加载产物：定义表 + 坏文件清单。 */
    public record LoadResult(Map<String, AgentDefinition> definitions, List<String> errors) {

        public LoadResult {
            definitions = definitions == null ? Map.of() : Collections.unmodifiableMap(definitions);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    private final Map<String, AgentDefinition> agents = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();

    private AgentLoader() {
    }

    // ───────────────────────── 对外 API ─────────────────────────

    /** 三级加载：内置 → 用户 → 项目。 */
    public static LoadResult loadAll(Path projectRoot) {
        return loadAll(projectRoot, List.of());
    }

    /**
     * 完整四级加载：插件 → 内置 → 用户 → 项目。
     *
     * @param projectRoot 项目根目录；为 null 时跳过项目级
     * @param pluginDirs  插件提供的定义目录；为 null 时按空处理
     */
    public static LoadResult loadAll(Path projectRoot, List<Path> pluginDirs) {
        var loader = new AgentLoader();
        if (pluginDirs != null) {
            for (Path dir : pluginDirs) {
                loader.loadDir(dir, AgentDefinition.SOURCE_PLUGIN);
            }
        }
        loader.loadBuiltins();

        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            loader.loadDir(Path.of(home, CONFIG_DIR, "agents"), AgentDefinition.SOURCE_USER);
        }
        if (projectRoot != null) {
            loader.loadDir(projectRoot.resolve(CONFIG_DIR).resolve("agents"),
                    AgentDefinition.SOURCE_PROJECT);
        }
        return new LoadResult(loader.agents, loader.errors);
    }

    /** 全部已加载的 Agent 类型名，按加载顺序（列表顺序稳定，供 schema 的 enum 用）。 */
    public static List<String> listNames(Map<String, AgentDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return List.of();
        }
        return List.copyOf(definitions.keySet());
    }

    /** 项目级定义目录（本项目固定为 {@code <root>/.agentjava/agents}）。 */
    public static Path projectAgentsDir(Path projectRoot) {
        return projectRoot == null ? null : projectRoot.resolve(CONFIG_DIR).resolve("agents");
    }

    /** 用户级定义目录。 */
    public static Path userAgentsDir() {
        String home = System.getProperty("user.home");
        return home == null || home.isEmpty() ? null : Path.of(home, CONFIG_DIR, "agents");
    }

    // ───────────────────────── 加载 ─────────────────────────

    private void loadBuiltins() {
        for (AgentDefinition def : AgentDefinition.builtins()) {
            agents.put(def.agentType(), def);
        }
    }

    private void loadDir(Path dir, String source) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        // 两层 catch 各管各的：内层管单个文件解析失败，外层管目录本身不可读。
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                try {
                    AgentDefinition def = parseAgentFile(path, source);
                    agents.put(def.agentType(), def);
                } catch (Exception e) {
                    errors.add("%s：%s".formatted(path, e.getMessage()));
                }
            }
        } catch (IOException e) {
            errors.add("%s：目录不可读（%s）".formatted(dir, e.getMessage()));
        }
    }

    // ───────────────────────── 解析 ─────────────────────────

    /**
     * 解析单个定义文件：可选的 YAML frontmatter + Markdown body。
     *
     * <p>frontmatter 的定位用两次 {@code indexOf("---")}——只要切出开头那一段，
     * 没必要为几行元数据引入完整的文档解析流程。
     *
     * <p>{@code name} 与 {@code description} 缺一个都抛异常：前者是 {@code subagent_type}
     * 的取值（没有它这条定义根本没法被选中），后者是主 Agent 判断「该不该派它」的唯一依据。
     *
     * <p><b>model 不做白名单校验</b>：只对 {@code inherit} 做大小写标准化，其余原样透传给
     * 路由层。第三方模型的名字五花八门，卡白名单只会挡住合法用法。
     */
    @SuppressWarnings("unchecked")
    static AgentDefinition parseAgentFile(Path path, String source) throws IOException {
        String content = Files.readString(path);
        String trimmed = content == null ? "" : content.strip();

        String yamlBlock = null;
        String body = trimmed;
        if (trimmed.startsWith("---")) {
            int end = trimmed.indexOf("---", 3);
            if (end >= 0) {
                yamlBlock = trimmed.substring(3, end).strip();
                body = trimmed.substring(end + 3).strip();
            }
        }

        String name = null;
        String description = null;
        List<String> tools = List.of();
        List<String> disallowedTools = List.of();
        String model = null;
        String permissionMode = null;
        int maxTurns = 0;

        if (yamlBlock != null && !yamlBlock.isEmpty()) {
            Map<String, Object> frontmatter = new Yaml().load(yamlBlock);
            if (frontmatter != null) {
                name = string(frontmatter, "name");
                description = string(frontmatter, "description");
                tools = stringList(frontmatter, "tools");
                disallowedTools = stringList(frontmatter, "disallowedTools");
                model = string(frontmatter, "model");
                permissionMode = string(frontmatter, "permissionMode");
                if (permissionMode == null) {
                    permissionMode = string(frontmatter, "permission_mode");
                }
                Object rawMaxTurns = frontmatter.get("maxTurns");
                if (rawMaxTurns == null) {
                    rawMaxTurns = frontmatter.get("max_turns");
                }
                if (rawMaxTurns instanceof Number n) {
                    maxTurns = n.intValue();
                }
            }
        }

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("缺少必填字段 name");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("缺少必填字段 description");
        }
        if (model != null) {
            model = model.strip();
            if (model.equalsIgnoreCase("inherit")) {
                model = "inherit";
            }
        }
        name = name.strip().toLowerCase(Locale.ROOT);

        return new AgentDefinition(name, description.strip(), tools, disallowedTools, model, maxTurns,
                AgentDefinition.parsePermissionMode(permissionMode),
                body.isEmpty() ? null : body,
                path.toString(), source);
    }

    private static String string(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : (v == null ? null : v.toString());
    }

    private static List<String> stringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            var out = new ArrayList<String>();
            for (Object item : list) {
                if (item != null) {
                    out.add(item.toString().strip());
                }
            }
            return List.copyOf(out);
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s.strip());
        }
        return List.of();
    }
}
