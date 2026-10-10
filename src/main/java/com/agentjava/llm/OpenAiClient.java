package com.agentjava.llm;

import com.agentjava.config.ProviderConfig;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.conversation.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *   <li>工具调用走 choices[0].delta.tool_calls，参数以 JSON 字符串碎片分片到达；</li>
 *   <li>配了 base_url 就覆盖端点，因此可以接 DeepSeek、Kimi、GLM 这类兼容服务。</li>
 * </ul>
 *
 * 按约定，thinking 只在 anthropic 协议生效，OpenAI 侧忽略该配置。
 */
public class OpenAiClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次请求允许生成的最大 token 数。 */
    protected static final int DEFAULT_MAX_TOKENS = 4096;

    private final OpenAIClient sdkClient;
    private final ProviderConfig config;
    private final String model;
    private String systemPrompt;

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
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt == null ? "" : prompt;
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
        var paramsBuilder = ChatCompletionCreateParams.builder()
                .model(model)
                .maxTokens(DEFAULT_MAX_TOKENS)
                // 让最后一个 chunk 带上 usage，这样才拿得到 token 用量
                .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
                .messages(buildMessages(conv.getMessages()));

        if (tools != null && !tools.isEmpty()) {
            for (var schema : tools) {
                paramsBuilder.addTool(buildTool(schema));
            }
        }

        int inputTokens = 0;
        int outputTokens = 0;

        // 流式解析 tool_calls：按 index 累积 id / name / arguments(JSON 碎片)
        var toolAcc = new LinkedHashMap<Integer, Accum>();

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
                var delta = chunk.choices().get(0).delta();
                if (delta.content().isPresent() && !delta.content().get().isEmpty()) {
                    offer(queue, new StreamEvent.TextDelta(delta.content().get()));
                }
                if (delta.toolCalls().isPresent()) {
                    for (var tc : delta.toolCalls().get()) {
                        int idx = (int) tc.index();
                        Accum acc = toolAcc.computeIfAbsent(idx, k -> new Accum());
                        tc.id().ifPresent(id -> acc.id = id);
                        tc.function().ifPresent(f -> {
                            f.name().ifPresent(n -> acc.name = n);
                            f.arguments().ifPresent(a -> {
                                acc.args.append(a);
                                offer(queue, new StreamEvent.ToolCallDelta(a));
                            });
                        });
                        if (!acc.started) {
                            boolean canStart = (acc.id != null && acc.name != null) || acc.args.length() > 0;
                            if (canStart) {
                                acc.started = true;
                                offer(queue, new StreamEvent.ToolCallStart(
                                        acc.id == null ? "" : acc.id, acc.name == null ? "" : acc.name));
                            }
                        }
                    }
                }
            }
        }

        // 流式结束，把累积的工具调用补全并发出 ToolCallComplete
        for (var acc : toolAcc.values()) {
            if (acc.id == null && acc.name == null && acc.args.length() == 0) {
                continue;
            }
            Map<String, Object> args;
            try {
                @SuppressWarnings("unchecked")
                var parsed = MAPPER.readValue(acc.args.toString(), Map.class);
                args = parsed;
            } catch (Exception e) {
                args = new java.util.HashMap<>();
            }
            offer(queue, new StreamEvent.ToolCallComplete(
                    acc.id == null ? "" : acc.id, acc.name == null ? "" : acc.name, args));
        }

        offer(queue, new StreamEvent.StreamEnd("stop", inputTokens, outputTokens));
    }

    /**
     * OpenAI 的 system 是消息列表的第一条，不是顶层参数——
     * 这是它和 Anthropic 最直观的一处差异。
     */
    protected List<ChatCompletionMessageParam> buildMessages(List<Message> messages) {
        var result = new ArrayList<ChatCompletionMessageParam>();
        if (!systemPrompt.isBlank()) {
            result.add(ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder().content(systemPrompt).build()));
        }
        for (var msg : messages) {
            if (msg.getStatus() == Message.Status.ERROR) {
                continue;
            }
            boolean hasToolUses = msg.getToolUses() != null && !msg.getToolUses().isEmpty();
            boolean hasToolResults = msg.getToolResults() != null && !msg.getToolResults().isEmpty();
            String text = msg.getContent() == null ? "" : msg.getContent();

            if ("assistant".equals(msg.getRole().apiValue())) {
                if (hasToolUses) {
                    var toolCalls = new ArrayList<ChatCompletionMessageToolCall>();
                    for (var tu : msg.getToolUses()) {
                        toolCalls.add(ChatCompletionMessageToolCall.ofFunction(
                                ChatCompletionMessageFunctionToolCall.builder()
                                        .id(tu.toolUseId())
                                        .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                                .name(tu.toolName())
                                                .arguments(toJsonString(tu.arguments()))
                                                .build())
                                        .build()));
                    }
                    var b = ChatCompletionAssistantMessageParam.builder().toolCalls(toolCalls);
                    if (!text.isBlank()) {
                        b.content(text);
                    }
                    result.add(ChatCompletionMessageParam.ofAssistant(b.build()));
                } else if (!text.isBlank()) {
                    result.add(ChatCompletionMessageParam.ofAssistant(
                            ChatCompletionAssistantMessageParam.builder().content(text).build()));
                }
            } else { // user
                if (hasToolResults) {
                    for (var tr : msg.getToolResults()) {
                        result.add(ChatCompletionMessageParam.ofTool(
                                ChatCompletionToolMessageParam.builder()
                                        .toolCallId(tr.toolUseId())
                                        .content(tr.content())
                                        .build()));
                    }
                } else if (!text.isBlank()) {
                    result.add(ChatCompletionMessageParam.ofUser(
                            ChatCompletionUserMessageParam.builder().content(text).build()));
                }
            }
        }
        return result;
    }

    /** 把一个工具 schema（{type, function:{name,description,parameters}}）转成 OpenAI 的 Tool 对象。 */
    private ChatCompletionTool buildTool(Map<String, Object> schema) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fn = (Map<String, Object>) schema.get("function");
        var fdBuilder = FunctionDefinition.builder().name((String) fn.get("name"));
        if (fn.containsKey("description")) {
            fdBuilder.description((String) fn.get("description"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) fn.getOrDefault("parameters", Map.of());
        var paramBuilder = FunctionParameters.builder();
        for (var entry : params.entrySet()) {
            paramBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        fdBuilder.parameters(paramBuilder.build());
        var ftBuilder = ChatCompletionFunctionTool.builder()
                .type(JsonValue.from("function"))
                .function(fdBuilder.build());
        return ChatCompletionTool.ofFunction(ftBuilder.build());
    }

    /** 把参数 Map 序列化成 OpenAI 要求的 JSON 字符串。 */
    private static String toJsonString(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
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

    /** 流式累积一个工具调用分片的临时结构。 */
    private static final class Accum {
        String id;
        String name;
        StringBuilder args = new StringBuilder();
        boolean started = false;
    }
}
