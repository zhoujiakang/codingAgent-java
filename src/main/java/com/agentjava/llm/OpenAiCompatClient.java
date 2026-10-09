package com.agentjava.llm;

import com.agentjava.config.ProviderConfig;

/**
 * OpenAI 兼容协议的适配器（如 DeepSeek、Kimi、GLM、MiniMax 等）。
 *
 * 这些服务照着 OpenAI 的接口实现，所以在协议层面和 {@link OpenAiClient} 完全一致；
 * 单独开一个类的意义是：将来某家兼容服务出现私有差异（参数名、流式格式、
 * 思考字段等），改动只落在这里，不会污染正统 OpenAI 的实现。
 *
 * 单独成类也让配置里的 protocol 语义是明确的：openai 是官方，openai-compat 是兼容端点。
 */
public class OpenAiCompatClient extends OpenAiClient {

    public OpenAiCompatClient(ProviderConfig cfg, String systemPrompt) {
        super(cfg, systemPrompt);
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
            throw new LlmException(
                    "protocol 为 openai-compat 时必须配置 base_url（provider '" + cfg.getName() + "'）。");
        }
    }
}
