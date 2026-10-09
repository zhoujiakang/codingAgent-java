package com.agentjava.llm;

import com.agentjava.config.ProviderConfig;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.conversation.Message;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * OpenAI Chat Completions API 适配器。
 *
 * 与 Anthropic 的那套规则完全不同，差异全部关在自己这里：
 * <ul>
 *   <li>system 不是顶层参数，而是消息列表的第一条；</li>
 *   <li>流式增量在 choices[0].delta.content 里，不在 content_block_delta 里；</li>
 *   <li>配了 base_url 就覆盖端点，因此可以接 DeepSeek、Kimi、GLM 这类兼容服务。</li>
 * </ul>
 *
 * 按约定，thinking 只在 anthropic 协议生效，OpenAI 侧忽略该配置。
 */
public class OpenAiClient implements LlmClient {

    /** 单次请求允许生成的最大 token 数。 */
    protected static final int DEFAULT_MAX_TOKENS = 4096;

    private final OpenAIClient sdkClient;
    private final ProviderConfig config;
    private final String model;
    private final String systemPrompt;

    public OpenAiClient(ProviderConfig cfg, String systemPrompt) {
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new LlmException.AuthenticationException(
                    "provider '" + cfg.getName() + "' 的 api_key 为空，请检查 .agentjava/config.yaml。");
        }
        var builder = OpenAIOkHttpClient.builder().apiKey(cfg.getApiKey());
        // 自定义端点覆盖（兼容端点如 DeepSeek / Kimi / GLM 走的就是这条路）
        if (cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()) {
            builder.baseUrl(cfg.getBaseUrl());
        }
        this.sdkClient = builder.build();
        this.config = cfg;
        this.model = cfg.getModel();
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
    }

    @Override
    public ProviderConfig config() {
        return config;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv) {
        return stream(conv, null);
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>(256);
        Thread.startVirtualThread(() -> {
            try {
                doStream(conv, queue);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                offer(queue, new StreamEvent.Error(classifyError(e)));
            }
        });
        return queue;
    }

    protected void doStream(ConversationManager conv, BlockingQueue<StreamEvent> queue) throws Exception {
        var paramsBuilder = ChatCompletionCreateParams.builder()
                .model(model)
                .maxTokens(DEFAULT_MAX_TOKENS)
                // 让最后一个 chunk 带上 usage，这样才拿得到 token 用量
                .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
                .messages(buildMessages(conv.toApiMessages()));

        int inputTokens = 0;
        int outputTokens = 0;

        try (StreamResponse<ChatCompletionChunk> response =
                     sdkClient.chat().completions().createStreaming(paramsBuilder.build())) {

            var iterator = response.stream().iterator();
            while (iterator.hasNext()) {
                ChatCompletionChunk chunk = iterator.next();

                // usage 只在带 stream_options 的最后一个 chunk 里出现
                if (chunk.usage().isPresent()) {
                    var usage = chunk.usage().get();
                    inputTokens = (int) usage.promptTokens();
                    outputTokens = (int) usage.completionTokens();
                }
                if (chunk.choices().isEmpty()) {
                    continue;
                }
                var content = chunk.choices().get(0).delta().content();
                if (content.isPresent() && !content.get().isEmpty()) {
                    offer(queue, new StreamEvent.TextDelta(content.get()));
                }
            }
        }

        offer(queue, new StreamEvent.StreamEnd("stop", inputTokens, outputTokens));
    }

    /**
     * OpenAI 的 system 是消息列表的第一条，不是顶层参数——
     * 这是它和 Anthropic 最直观的一处差异。
     */
    protected List<ChatCompletionMessageParam> buildMessages(List<Message.ApiMessage> apiMessages) {
        var messages = new ArrayList<ChatCompletionMessageParam>();
        if (!systemPrompt.isBlank()) {
            messages.add(ChatCompletionMessageParam.ofSystem(
                    com.openai.models.chat.completions.ChatCompletionSystemMessageParam.builder()
                            .content(systemPrompt)
                            .build()));
        }
        for (var m : apiMessages) {
            if ("user".equals(m.role())) {
                messages.add(ChatCompletionMessageParam.ofUser(
                        com.openai.models.chat.completions.ChatCompletionUserMessageParam.builder()
                                .content(m.content())
                                .build()));
            } else {
                messages.add(ChatCompletionMessageParam.ofAssistant(
                        com.openai.models.chat.completions.ChatCompletionAssistantMessageParam.builder()
                                .content(m.content())
                                .build()));
            }
        }
        return messages;
    }

    protected void offer(BlockingQueue<StreamEvent> queue, StreamEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把 SDK 异常翻译成一句人能看懂的话。 */
    protected String classifyError(Throwable e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = msg.toLowerCase();
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("incorrect api key")) {
            return "鉴权失败（401）：请检查 provider '" + config.getName() + "' 的 api_key 是否正确。";
        }
        if (lower.contains("429") || lower.contains("rate limit") || lower.contains("rate_limit")) {
            return "请求被限流（429）：稍后再试，或换一个 provider。";
        }
        if (lower.contains("404") || lower.contains("does not exist") || lower.contains("not found")) {
            return "模型不存在（404）：请检查 provider '" + config.getName() + "' 的 model 配置。";
        }
        if (lower.contains("connection") || lower.contains("timeout") || lower.contains("unable to resolve")) {
            return "网络异常，连不上 " + (config.getBaseUrl() == null ? "默认端点" : config.getBaseUrl()) + "：" + msg;
        }
        return "请求失败：" + msg;
    }
}
