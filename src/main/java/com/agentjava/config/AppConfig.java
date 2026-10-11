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

    /**
     * 是否启用 coordinator 模式。开启后 Lead 的工具集收窄到纯调度
     * （Agent / SendMessage / TaskStop / SyntheticOutput / TeamDelete），
     * 并每轮注入调度指引。只在配置里定，会话中途不切换。
     */
    private boolean enableCoordinatorMode = false;

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

    public boolean isEnableCoordinatorMode() {
        return enableCoordinatorMode;
    }

    public void setEnableCoordinatorMode(boolean enableCoordinatorMode) {
        this.enableCoordinatorMode = enableCoordinatorMode;
    }
}
