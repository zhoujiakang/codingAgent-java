package com.agentjava.llm;

import com.agentjava.config.ProviderConfig;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.conversation.Message;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.ThinkingConfigEnabled;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Anthropic Messages API 适配器。
 *
 * 干的事就一件：翻译。
 * 往外发请求时把内部消息翻译成 Anthropic 的 MessageParam；
 * 收到 SSE 事件时翻译成统一的 {@link StreamEvent}。
 * 这些差异全部关在本类里，上层完全看不到 anthropic-java 的影子。
 */
public class AnthropicClient implements LlmClient {

    /** 单次请求允许生成的最大 token 数。 */
    protected static final int DEFAULT_MAX_TOKENS = 4096;

    /** 扩展思考的 token 预算；必须小于 max_tokens，这里取一半。 */
    protected static final int DEFAULT_THINKING_BUDGET = 2048;

    // 注意：SDK 的客户端类型也叫 AnthropicClient，与本类同名，所以这里写全限定名
    private final com.anthropic.client.AnthropicClient sdkClient;
    private final ProviderConfig config;
    private final String model;
    private final boolean thinking;
    private final String systemPrompt;

    public AnthropicClient(ProviderConfig cfg, String systemPrompt) {
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new LlmException.AuthenticationException(
                    "provider '" + cfg.getName() + "' 的 api_key 为空，请检查 .agentjava/config.yaml。");
        }
        var builder = AnthropicOkHttpClient.builder().apiKey(cfg.getApiKey());
        // 配了自定义端点就覆盖，没配就用 SDK 默认端点（F3）
        if (cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()) {
            builder.baseUrl(cfg.getBaseUrl());
        }
        this.sdkClient = builder.build();
        this.config = cfg;
        this.model = cfg.getModel();
        this.thinking = cfg.isThinking();
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
        // 有界队列：生产快、消费慢时让生产者自然阻塞，避免在内存里堆一大堆事件
        BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>(256);

        // virtual thread 跑流式循环：成本极低，等价于 Go 里的 goroutine，
        // 与 TUI 主线程互不阻塞（N1）
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
        var paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(DEFAULT_MAX_TOKENS)
                // Anthropic 把 system 提到顶层参数，而不是塞进 messages（与 OpenAI 不同）
                .system(systemPrompt)
                .messages(buildMessages(conv.toApiMessages()));

        if (thinking) {
            paramsBuilder.thinking(ThinkingConfigEnabled.builder()
                    .budgetTokens(DEFAULT_THINKING_BUDGET)
                    .build());
        }

        int inputTokens = 0;
        int outputTokens = 0;
        String stopReason = "end_turn";

        try (StreamResponse<RawMessageStreamEvent> response =
                     sdkClient.messages().createStreaming(paramsBuilder.build())) {

            var iterator = response.stream().iterator();
            while (iterator.hasNext()) {
                var event = iterator.next();

                if (event.isMessageStart()) {
                    // message_start 带着 input_tokens（轮次越多这个数字越大）
                    inputTokens = (int) event.asMessageStart().message().usage().inputTokens();
                } else if (event.isContentBlockDelta()) {
                    var delta = event.asContentBlockDelta().delta();
                    if (delta.isText()) {
                        offer(queue, new StreamEvent.TextDelta(delta.asText().text()));
                    } else if (delta.isThinking()) {
                        // 思考增量：接收即丢弃，绝不混入正文（F5）
                        offer(queue, new StreamEvent.ThinkingDelta(delta.asThinking().thinking()));
                    }
                } else if (event.isMessageDelta()) {
                    var delta = event.asMessageDelta().delta();
                    if (delta.stopReason().isPresent()) {
                        stopReason = delta.stopReason().get().asString();
                    }
                    outputTokens = (int) event.asMessageDelta().usage().outputTokens();
                }
            }
        }

        offer(queue, new StreamEvent.StreamEnd(stopReason, inputTokens, outputTokens));
    }

    /** 把内部消息转成 Anthropic 的消息参数。 */
    protected List<MessageParam> buildMessages(List<Message.ApiMessage> apiMessages) {
        var result = new ArrayList<MessageParam>();
        for (var m : apiMessages) {
            result.add(MessageParam.builder()
                    .role("user".equals(m.role()) ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT)
                    .content(m.content())
                    .build());
        }
        return result;
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
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("authentication")) {
            return "鉴权失败（401）：请检查 provider '" + config.getName() + "' 的 api_key 是否正确。";
        }
        if (lower.contains("429") || lower.contains("rate limit") || lower.contains("rate_limit")) {
            return "请求被限流（429）：稍后再试，或换一个 provider。";
        }
        if (lower.contains("404") || lower.contains("not_found") || lower.contains("not found")) {
            return "模型不存在（404）：请检查 provider '" + config.getName() + "' 的 model 配置。";
        }
        if (lower.contains("connection") || lower.contains("timeout") || lower.contains("unable to resolve")) {
            return "网络异常，连不上 " + (config.getBaseUrl() == null ? "默认端点" : config.getBaseUrl()) + "：" + msg;
        }
        return "请求失败：" + msg;
    }
}
