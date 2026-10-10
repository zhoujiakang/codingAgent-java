package com.agentjava.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentjava.config.ProviderConfig;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.conversation.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次请求允许生成的最大 token 数。 */
    protected static final int DEFAULT_MAX_TOKENS = 4096;

    /** 扩展思考的 token 预算；必须小于 max_tokens，这里取一半。 */
    protected static final int DEFAULT_THINKING_BUDGET = 2048;

    // 注意：SDK 的客户端类型也叫 AnthropicClient，与本类同名，所以这里写全限定名
    private final com.anthropic.client.AnthropicClient sdkClient;
    private final ProviderConfig config;
    private final String model;
    private final boolean thinking;
    private String systemPrompt;

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
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt == null ? "" : prompt;
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
                doStream(conv, tools, queue);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                offer(queue, new StreamEvent.Error(classifyError(e)));
            }
        });
        return queue;
    }

    protected void doStream(ConversationManager conv, List<Map<String, Object>> tools,
                            BlockingQueue<StreamEvent> queue) throws Exception {
        var paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(DEFAULT_MAX_TOKENS)
                // Anthropic 把 system 提到顶层参数，而不是塞进 messages（与 OpenAI 不同）。
                // 给 system 块打上 cache_control: ephemeral，让它进入 Prompt Cache，
                // Agent Loop 每轮 system 不变即可命中缓存，大幅降低 input token 成本。
                .messages(buildMessages(conv.getMessages()));

        if (!systemPrompt.isBlank()) {
            paramsBuilder.systemOfTextBlockParams(List.of(
                    TextBlockParam.builder()
                            .text(systemPrompt)
                            .cacheControl(CacheControlEphemeral.builder()
                                    .type(JsonValue.from("ephemeral"))
                                    .build())
                            .build()));
        } else {
            paramsBuilder.system("");
        }

        if (thinking) {
            paramsBuilder.thinking(ThinkingConfigEnabled.builder()
                    .budgetTokens(DEFAULT_THINKING_BUDGET)
                    .build());
        }

        // 把工具 schema 发给模型——这是 Function Calling 让模型"知道能调什么"的关键。
        // 在最后一个工具上打 cache_control，缓存前缀（含 system + 此前所有工具），
        // 工具列表稳定时也能命中缓存。
        if (tools != null && !tools.isEmpty()) {
            int last = tools.size() - 1;
            for (int i = 0; i < tools.size(); i++) {
                paramsBuilder.addTool(buildTool(tools.get(i), i == last));
            }
        }

        int inputTokens = 0;
        int outputTokens = 0;
        String stopReason = "end_turn";

        // 流式解析 tool_use：跨多个 delta 累积的 JSON 碎片
        String currentToolName = "";
        String currentToolId = "";
        var jsonAccum = new StringBuilder();

        try (StreamResponse<RawMessageStreamEvent> response =
                     sdkClient.messages().createStreaming(paramsBuilder.build())) {

            var iterator = response.stream().iterator();
            while (iterator.hasNext()) {
                var event = iterator.next();

                if (event.isContentBlockStart()) {
                    var block = event.asContentBlockStart().contentBlock();
                    if (block.isToolUse()) {
                        var tu = block.asToolUse();
                        currentToolName = tu.name();
                        currentToolId = tu.id();
                        jsonAccum.setLength(0);
                        offer(queue, new StreamEvent.ToolCallStart(currentToolId, currentToolName));
                    }
                } else if (event.isContentBlockDelta()) {
                    var delta = event.asContentBlockDelta().delta();
                    if (delta.isText()) {
                        offer(queue, new StreamEvent.TextDelta(delta.asText().text()));
                    } else if (delta.isThinking()) {
                        // 思考增量：接收即丢弃，绝不混入正文（F5）
                        // delta.asThinking().thinking() 这里不消费，仅占位
                    } else if (delta.isInputJson()) {
                        // 累积工具参数的 JSON 碎片
                        String partial = delta.asInputJson().partialJson();
                        jsonAccum.append(partial);
                        offer(queue, new StreamEvent.ToolCallDelta(partial));
                    }
                } else if (event.isContentBlockStop()) {
                    if (!currentToolName.isEmpty()) {
                        Map<String, Object> args;
                        try {
                            @SuppressWarnings("unchecked")
                            var parsed = MAPPER.readValue(jsonAccum.toString(), Map.class);
                            args = parsed;
                        } catch (Exception e) {
                            // 容错：JSON 拼接失败不崩溃，用空 Map 兜底，模型看到后重试
                            args = new java.util.HashMap<>();
                        }
                        offer(queue, new StreamEvent.ToolCallComplete(currentToolId, currentToolName, args));
                        currentToolName = "";
                        currentToolId = "";
                        jsonAccum.setLength(0);
                    }
                } else if (event.isMessageStart()) {
                    inputTokens = (int) event.asMessageStart().message().usage().inputTokens();
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

    /** 把内部消息转成 Anthropic 的消息参数，支持文本、tool_use、tool_result 三种块。 */
    protected List<MessageParam> buildMessages(List<Message> messages) {
        var result = new ArrayList<MessageParam>();
        for (var msg : messages) {
            if (msg.getStatus() == Message.Status.ERROR) {
                continue;
            }
            boolean hasToolUses = msg.getToolUses() != null && !msg.getToolUses().isEmpty();
            boolean hasToolResults = msg.getToolResults() != null && !msg.getToolResults().isEmpty();
            String text = msg.getContent() == null ? "" : msg.getContent();

            if ("assistant".equals(msg.getRole().apiValue())) {
                if (hasToolUses) {
                    var content = new ArrayList<ContentBlockParam>();
                    if (!text.isBlank()) {
                        content.add(ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()));
                    }
                    for (var tu : msg.getToolUses()) {
                        content.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                                .id(tu.toolUseId())
                                .name(tu.toolName())
                                .input(JsonValue.from(tu.arguments()))
                                .build()));
                    }
                    result.add(MessageParam.builder()
                            .role(MessageParam.Role.ASSISTANT)
                            .contentOfBlockParams(content)
                            .build());
                } else if (!text.isBlank()) {
                    result.add(MessageParam.builder()
                            .role(MessageParam.Role.ASSISTANT)
                            .content(text)
                            .build());
                }
            } else { // user
                if (hasToolResults) {
                    var content = new ArrayList<ContentBlockParam>();
                    for (var tr : msg.getToolResults()) {
                        var b = ToolResultBlockParam.builder()
                                .toolUseId(tr.toolUseId())
                                .isError(tr.isError())
                                .content(tr.content());
                        content.add(ContentBlockParam.ofToolResult(b.build()));
                    }
                    result.add(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(content)
                            .build());
                } else if (!text.isBlank()) {
                    result.add(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content(text)
                            .build());
                }
            }
        }
        return result;
    }

    /** 把一个工具 schema（{name, description, input_schema}）转成 Anthropic 的 Tool 对象。 */
    private Tool buildTool(Map<String, Object> schema, boolean cache) {
        var builder = Tool.builder().name((String) schema.get("name"));
        if (schema.containsKey("description")) {
            builder.description((String) schema.get("description"));
        }
        var inputSchema = (Map<String, Object>) schema.getOrDefault(
                "input_schema", Map.of("type", "object", "properties", Map.of()));
        builder.inputSchema(Tool.InputSchema.builder()
                .type(JsonValue.from(inputSchema.getOrDefault("type", "object")))
                .putAllAdditionalProperties(toJsonValueMap(inputSchema))
                .build());
        // 仅在最后一个工具上打断点：缓存前缀包含 system 与此前所有工具定义
        if (cache) {
            builder.cacheControl(CacheControlEphemeral.builder()
                    .type(JsonValue.from("ephemeral"))
                    .build());
        }
        return builder.build();
    }

    /** 把 input_schema 的其余字段（properties、required 等）转成 SDK 的 JsonValue 映射。 */
    private static Map<String, JsonValue> toJsonValueMap(Map<String, Object> map) {
        var result = new LinkedHashMap<String, JsonValue>();
        for (var entry : map.entrySet()) {
            if ("type".equals(entry.getKey())) {
                continue;
            }
            result.put(entry.getKey(), JsonValue.from(entry.getValue()));
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
