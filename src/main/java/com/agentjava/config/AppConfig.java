package com.agentjava.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置根对象。
 *
 * 目前只关心 providers 列表；权限模式、MCP 等配置段也在此扩展。
 */
public class AppConfig {

    private List<ProviderConfig> providers = new ArrayList<>();

    public AppConfig() {
    }

    public AppConfig(List<ProviderConfig> providers) {
        this.providers = providers;
    }

    public List<ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(List<ProviderConfig> providers) {
        this.providers = providers;
    }
}
