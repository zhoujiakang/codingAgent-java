package com.agentjava.config;

/**
 * 单个 LLM 供应商的配置项。
 *
 * 对应 YAML 中 providers 列表里的一个元素，六个字段：
 * name / protocol / base_url / api_key / model / thinking。
 */
public class ProviderConfig {

    /** 供应商标识名，方便区分多个配置（状态栏左侧显示）。 */
    private String name;

    /** 协议类型：anthropic | openai | openai-compat。 */
    private String protocol;

    /** 自定义端点地址；为空时使用 SDK 默认端点。 */
    private String baseUrl;

    /** 认证密钥。 */
    private String apiKey;

    /** 模型名（状态栏右侧显示）。 */
    private String model;

    /** 是否启用扩展思考；仅 anthropic 协议生效。 */
    private boolean thinking;

    /** 上下文窗口大小（token）。压缩阈值据此计算，默认 200000（Claude 的窗口量级）。 */
    private int contextWindow = 200_000;

    public ProviderConfig() {
    }

    public ProviderConfig(String name, String protocol, String baseUrl, String apiKey, String model, boolean thinking) {
        this.name = name;
        this.protocol = protocol;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.thinking = thinking;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isThinking() {
        return thinking;
    }

    public void setThinking(boolean thinking) {
        this.thinking = thinking;
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(int contextWindow) {
        if (contextWindow > 0) {
            this.contextWindow = contextWindow;
        }
    }

    /** 去掉首尾空白后的协议名，便于做不区分大小写的比较。 */
    public String normalizedProtocol() {
        return protocol == null ? "" : protocol.trim().toLowerCase();
    }

    @Override
    public String toString() {
        // 注意：这里刻意不输出 apiKey，避免密钥被打印到界面或日志（N5 密钥安全）
        return "ProviderConfig{name='" + name + "', protocol='" + protocol
                + "', model='" + model + "', baseUrl='" + baseUrl
                + "', thinking=" + thinking + "}";
    }
}
