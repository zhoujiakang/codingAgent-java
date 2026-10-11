package com.agentjava.memory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 记忆召回：由 LLM 从候选清单里挑出与当前查询相关的若干条，再把全文读出来。
 *
 * <p>为什么要多这一步：注入阶段只把<b>索引</b>塞进了上下文，模型知道有这些记忆存在，
 * 但看不到内容。全量塞正文显然不行——几十条记忆的全文比对话本身还长。
 * 所以按需召回：先用一份廉价清单让 LLM 点名，再只把被点名的几条读出来。
 *
 * <p>{@link SelectorFn} 是回调接口，把记忆模块和 LLM 客户端解耦：这里不需要知道用的是
 * Anthropic 还是 OpenAI，调用方传一个闭包进来做一次独立的侧查询即可。
 */
public final class MemoryRecall {

    /** 单次召回最多返回几条记忆。 */
    public static final int MAX_SELECTED = 5;

    private static final String SELECTOR_SYSTEM =
            "You are a memory recall assistant. Given a list of memory files and a user query, "
                    + "select the memories that would genuinely help answer the query. "
                    + "Respond with JSON only: {\"selected_memories\": [\"file1.md\", ...]}. "
                    + "Select at most 5. If none are relevant, respond with an empty list.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MemoryRecall() {
    }

    /** 一次独立的 LLM 侧查询。由调用方注入实现，避免记忆模块依赖具体的 LLM 客户端。 */
    @FunctionalInterface
    public interface SelectorFn {
        /**
         * @param systemPrompt 选择器系统提示词
         * @param userMessage  含候选清单与查询的用户消息
         * @return LLM 的原始文本响应
         */
        String select(String systemPrompt, String userMessage) throws Exception;
    }

    /** 被选中的一条记忆：路径用于回头读全文，mtime 用于算新鲜度。 */
    public record RelevantMemory(String path, String filename, long mtimeMs) {
    }

    /**
     * 找出与查询相关的记忆。
     *
     * @param query           当前用户查询
     * @param userMemDir      用户级记忆目录，可为 null
     * @param projectMemDir   项目级记忆目录，可为 null
     * @param recentTools     最近用过的工具名，给选择器一个额外的相关性信号
     * @param alreadySurfaced 本会话已经展示过的记忆路径，避免重复召回
     * @param selector        LLM 选择器回调
     */
    public static List<RelevantMemory> findRelevantMemories(
            String query, Path userMemDir, Path projectMemDir,
            List<String> recentTools, Set<String> alreadySurfaced,
            SelectorFn selector) {

        List<MemoryScanner.MemoryHeader> all = new ArrayList<>();
        if (userMemDir != null) {
            all.addAll(MemoryScanner.scanMemoryFiles(userMemDir, "user"));
        }
        if (projectMemDir != null) {
            all.addAll(MemoryScanner.scanMemoryFiles(projectMemDir, "project"));
        }

        Set<String> surfaced = alreadySurfaced == null ? Set.of() : alreadySurfaced;
        List<MemoryScanner.MemoryHeader> candidates = new ArrayList<>();
        for (var m : all) {
            if (!surfaced.contains(m.filePath())) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> selected = selectRelevantMemories(query, candidates, recentTools, selector);
        if (selected.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<RelevantMemory>(selected.size());
        for (String filename : selected) {
            for (var h : candidates) {
                if (h.filename().equals(filename)) {
                    out.add(new RelevantMemory(h.filePath(), h.filename(), h.mtimeMs()));
                    break;
                }
            }
        }
        return out;
    }

    static List<String> selectRelevantMemories(String query,
                                               List<MemoryScanner.MemoryHeader> candidates,
                                               List<String> recentTools,
                                               SelectorFn selector) {
        String manifest = MemoryScanner.buildManifest(candidates);
        String prompt = buildSelectionPrompt(manifest, query, recentTools);
        String raw;
        try {
            raw = selector.select(SELECTOR_SYSTEM, prompt);
        } catch (Exception e) {
            return List.of();
        }
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String json = extractJsonObject(raw);
        if (json.isEmpty()) {
            return List.of();
        }
        try {
            var arr = MAPPER.readTree(json).get("selected_memories");
            if (arr == null || !arr.isArray()) {
                return List.of();
            }
            // 只接受候选清单里确实存在的名字：LLM 偶尔会编文件名
            Set<String> allowed = new LinkedHashSet<>();
            for (var h : candidates) {
                allowed.add(h.filename());
            }
            var out = new ArrayList<String>();
            for (var node : arr) {
                String filename = node.asText();
                if (allowed.contains(filename) && !out.contains(filename)) {
                    out.add(filename);
                }
                if (out.size() >= MAX_SELECTED) {
                    break;
                }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String buildSelectionPrompt(String manifest, String query,
                                               List<String> recentTools) {
        var sb = new StringBuilder();
        sb.append("## Available memories\n\n").append(manifest).append('\n');
        if (recentTools != null && !recentTools.isEmpty()) {
            sb.append("## Recently used tools\n\n").append(String.join(", ", recentTools))
                    .append("\n\n");
        }
        sb.append("## User query\n\n").append(query).append('\n');
        return sb.toString();
    }

    /**
     * 从响应文本里抠出第一个完整的 JSON 对象。
     *
     * <p>LLM 经常在 JSON 外面包一堆东西：markdown 代码块、一句"以下是我的选择"、
     * 甚至几行解释。这里做的是平衡扫描而不是简单的 indexOf('{') + lastIndexOf('}')：
     * 后面那种写法在响应里出现两个 JSON 时会把中间的解释文字也算进去。
     */
    static String extractJsonObject(String text) {
        if (text == null) {
            return "";
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    /**
     * 把选中的记忆全文渲染成一段提醒。
     *
     * <p>每条前面带标题、保存时间与新鲜度提醒。超过一天的记忆会额外附一段
     * "这只是某一时刻的观察，用之前先验证当前代码"——见 {@link MemoryAge}。
     */
    public static String renderReminder(List<RelevantMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        sb.append("The following relevant memories from prior conversations may help:\n\n");
        for (var mem : memories) {
            String content;
            try {
                content = Files.readString(Path.of(mem.path()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            sb.append("## Memory: ").append(mem.filename())
                    .append(" (saved ").append(MemoryAge.age(mem.mtimeMs())).append(")\n\n");
            String note = MemoryAge.freshnessText(mem.mtimeMs());
            if (!note.isEmpty()) {
                sb.append(note).append("\n\n");
            }
            sb.append(content).append("\n\n---\n\n");
        }
        return sb.toString();
    }
}
