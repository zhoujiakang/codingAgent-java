package com.agentjava.llm;

import com.agentjava.config.ConfigException;
import com.agentjava.config.ProviderConfig;
import com.agentjava.conversation.ConversationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * 统一的 LLM 客户端接口。
 *
 * 对外暴露的是领域语义（"给我一次流式对话"），而不是"调 Anthropic 的 messages 接口"。
 * 底层用哪家 SDK、走哪家协议，全部关在各自的实现里。
 */
public interface LlmClient {

    /**
     * 发起一次流式对话。
     *
     * @param conv 对话历史（内部消息模型，实现类负责转成本协议的格式）
     * @return 事件队列；生产者跑在 virtual thread 里，消费者逐个取即可。
     *         队列里最后一个事件一定是 {@link StreamEvent.StreamEnd} 或 {@link StreamEvent.Error}。
     */
    BlockingQueue<StreamEvent> stream(ConversationManager conv);

    /**
     * 带工具定义的流式对话。
     *
     * 当前不做工具调用，实现会忽略 tools 参数；签名先留在这里，
     * 等接入工具系统时不用再改接口。
     */
    BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools);

    /** 当前 provider 配置。 */
    ProviderConfig config();

    /**
     * 运行时覆盖系统提示词。
     *
     * 主要用于 Plan Mode：进入计划模式时把"只读/只规划"的指引拼到系统提示词后面，
     * 退出时再还原。实现类自行保存并在下一次请求中生效。
     */
    void setSystemPrompt(String prompt);

    /**
     * 按协议创建对应的客户端实现。
     *
     * @param cfg           provider 配置
     * @param systemPrompt  系统提示词，由调用方注入
     * @return 协议对应的客户端
     */
    static LlmClient create(ProviderConfig cfg, String systemPrompt) {
        return switch (cfg.normalizedProtocol()) {
            case "anthropic" -> new AnthropicClient(cfg, systemPrompt);
            case "openai" -> new OpenAiClient(cfg, systemPrompt);
            case "openai-compat" -> new OpenAiCompatClient(cfg, systemPrompt);
            default -> throw new ConfigException("不支持的协议：" + cfg.getProtocol());
        };
    }
}
