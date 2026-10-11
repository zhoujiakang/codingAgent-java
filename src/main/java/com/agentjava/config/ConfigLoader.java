package com.agentjava.config;

import com.agentjava.mcp.McpServerConfig;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置加载与校验。
 *
 * 读取 YAML 配置文件，把 providers 列表绑定成 {@link ProviderConfig}，
 * 并在启动期做必要项校验。任何不合法都抛 {@link ConfigException}，
 * 由入口层转成一句可读提示后退出，而不是把异常栈甩给用户（N4）。
 */
public final class ConfigLoader {

    /** 项目级配置：相对当前工作目录。 */
    public static final String DEFAULT_PATH = ".agentjava/config.yaml";

    /**
     * 全局配置：家目录下的同一份结构。
     *
     * 有了它，在任意目录启动都能用同一个 agent——否则换个目录就得重新拷一份配置，
     * 密钥也会散落在各个项目里。项目级配置优先，方便某个项目单独换 provider 或模型。
     */
    public static final String GLOBAL_PATH = System.getProperty("user.home") + "/.agentjava/config.yaml";

    /** 支持的协议类型。 */
    public static final Set<String> SUPPORTED_PROTOCOLS = Set.of("anthropic", "openai", "openai-compat");

    private ConfigLoader() {
    }

    /**
     * 解析该用哪一份配置：项目级优先，找不到再退回全局级。
     *
     * @return 实际存在的配置文件路径；都不存在时返回项目级路径，好让错误提示指向它
     */
    public static String resolveConfigPath() {
        Path project = Path.of(DEFAULT_PATH);
        if (Files.isRegularFile(project)) {
            return DEFAULT_PATH;
        }
        if (Files.isRegularFile(Path.of(GLOBAL_PATH))) {
            return GLOBAL_PATH;
        }
        return DEFAULT_PATH;
    }

    /**
     * 加载并校验配置。
     *
     * @param path 配置文件路径
     * @return 校验通过的配置
     * @throws ConfigException 文件缺失、YAML 格式错误、字段缺失或不合法
     */
    public static AppConfig load(String path) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            throw new ConfigException("找不到配置文件，已尝试：\n"
                    + "  1. " + projectAbsolute() + "（项目级）\n"
                    + "  2. " + GLOBAL_PATH + "（全局级）\n"
                    + "任选一处放一份 config.yaml 即可，模板见 "
                    + "agent_java/.agentjava/config.yaml.example。");
        }

        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("读取配置文件失败：" + file.toAbsolutePath() + "（" + e.getMessage() + "）", e);
        }

        Map<String, Object> raw;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new Yaml().load(text);
            raw = parsed;
        } catch (Exception e) {
            throw new ConfigException("配置文件不是合法的 YAML：" + e.getMessage(), e);
        }
        if (raw == null) {
            throw new ConfigException("配置文件内容为空，至少需要配置一个 provider。");
        }

        Object providersNode = raw.get("providers");
        if (!(providersNode instanceof List<?> list) || list.isEmpty()) {
            throw new ConfigException("配置缺少 providers，或 providers 为空列表；至少需要配置一个 provider。");
        }

        List<ProviderConfig> providers = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            providers.add(parseProvider(list.get(i), i));
        }

        AppConfig config = new AppConfig();
        config.setProviders(providers);
        // coordinator 模式开关，缺省关闭
        config.setEnableCoordinatorMode(bool(raw.get("enable_coordinator_mode")));
        return config;
    }

    @SuppressWarnings("unchecked")
    private static ProviderConfig parseProvider(Object node, int index) {
        if (!(node instanceof Map<?, ?> map)) {
            throw new ConfigException("providers[" + index + "] 必须是一个对象（包含 name/protocol/api_key/model 等字段）。");
        }
        Map<String, Object> m = (Map<String, Object>) map;

        ProviderConfig cfg = new ProviderConfig();
        cfg.setName(str(m.get("name")));
        cfg.setProtocol(str(m.get("protocol")));
        cfg.setBaseUrl(str(m.get("base_url")));
        cfg.setApiKey(str(m.get("api_key")));
        cfg.setModel(str(m.get("model")));
        cfg.setThinking(bool(m.get("thinking")));
        cfg.setContextWindow(intVal(m.get("context_window"), 200_000));

        // 逐项校验必要项，缺哪一项就点名哪一项
        require(index, "name", cfg.getName());
        require(index, "protocol", cfg.getProtocol());
        require(index, "api_key", cfg.getApiKey());
        require(index, "model", cfg.getModel());

        if (!SUPPORTED_PROTOCOLS.contains(cfg.normalizedProtocol())) {
            throw new ConfigException("providers[" + index + "].protocol 不合法：'" + cfg.getProtocol()
                    + "'，可选值为 " + String.join(" / ", SUPPORTED_PROTOCOLS) + "。");
        }
        return cfg;
    }

    /** 项目级配置的绝对路径，用于错误提示。 */
    private static String projectAbsolute() {
        return Path.of(DEFAULT_PATH).toAbsolutePath().toString();
    }

    private static void require(int index, String field, String value) {
        if (value == null || value.isBlank()) {
            throw new ConfigException("providers[" + index + "]." + field + " 不能为空。");
        }
    }

    /** 把 YAML 里的标量安全地转成字符串；null 或空白一律返回 null，交给校验去点名。 */
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

    /** 把 YAML 里的标量安全地转成 int；null、空白或非法值一律回退到默认值。 */
    private static int intVal(Object value, int def) {
        if (value == null) {
            return def;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 载入并合并 MCP Server 配置。
     *
     * <p>采用「用户级先入、项目级覆盖」的合并策略：全局 {@code ~/.agentjava/config.yaml} 与项目级
     * {@code .agentjava/config.yaml} 都可能有 {@code mcp_servers} 段；同名 Server 项目级覆盖用户级，
     * 和 Git 的 {@code --local} 优先于 {@code --global} 同一思路。两份都不存在时返回空列表（即不接任何 Server）。
     */
    public static List<McpServerConfig> loadMcpServers() {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        mergeMcpServers(merged, GLOBAL_PATH);   // 全局先入
        mergeMcpServers(merged, DEFAULT_PATH);   // 项目覆盖
        List<McpServerConfig> out = new ArrayList<>();
        for (var e : merged.entrySet()) {
            out.add(McpServerConfig.fromYaml(e.getKey(), e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void mergeMcpServers(Map<String, Map<String, Object>> merged, String path) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            return;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        Map<String, Object> raw;
        try {
            raw = new Yaml().load(text);
        } catch (Exception e) {
            return;
        }
        if (raw == null) {
            return;
        }
        Object servers = raw.get("mcp_servers");
        if (!(servers instanceof Map<?, ?> m)) {
            return;
        }
        for (var entry : ((Map<String, Object>) m).entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> v) {
                merged.put(entry.getKey(), (Map<String, Object>) v);
            }
        }
    }
}
