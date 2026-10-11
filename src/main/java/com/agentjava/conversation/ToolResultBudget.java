package com.agentjava.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第一层压缩：工具结果预算（Layer 1）。
 *
 * <p>全部处理发生在「工具结果进入对话历史之前」。先按本轮 tool calls 算出豁免集
 * （模型回读之前溢写的文件时，读回内容同样巨大，若照常溢写就永远看不到全文），
 * 然后逐条做单条检查，最后整批做聚合检查。处理完才写入历史。
 *
 * <p>关键原则：「写入即终态」。被替换成预览的 tool result 从此不再变化，
 * 后续每一轮发给 API 的请求里，老消息逐字节和上轮相同，Prompt Cache 的前缀天然稳定。
 */
public final class ToolResultBudget {

    /**
     * 单条工具结果进入对话历史前的溢写阈值：超过这个字符数就写盘留预览。
     * 定在 50_000，让模型一次能看到足够多内容，不必为了看全结果再发一轮 ReadFile。
     * 与 {@link com.agentjava.tool.ToolRegistry#MAX_OUTPUT_CHARS} 同值，这里引用常量避免漂移。
     */
    public static final int SINGLE_OUTPUT_CHARS = com.agentjava.tool.ToolRegistry.MAX_OUTPUT_CHARS;

    /** 单条消息内所有工具结果的聚合上限（字符数）。一轮并行工具的结果合计不能超过这个量。 */
    public static final int MESSAGE_AGGREGATE_LIMIT = 200_000;

    /** 落盘预览截取的前 N 个字符。 */
    private static final int PREVIEW_CHARS = 2_048;

    private ToolResultBudget() {
    }

    /** 计算溢写目录：{@code <workDir>/.agentjava/sessions/<sessionId>/tool-results}。 */
    public static Path spillDir(String workDir, String sessionId) {
        String base = (workDir == null || workDir.isBlank())
                ? System.getProperty("user.dir") : workDir;
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        return Path.of(base, ".agentjava", "sessions", sid, "tool-results");
    }

    /**
     * 回读豁免判定：工具是 ReadFile 且目标路径落在溢写目录下，说明模型在回读之前溢写的文件。
     * 此时读回的内容与原始结果一样大，若照常溢写，模型拿到的又是一个预览加路径，永远看不到全文。
     *
     * <p>路径先 normalize 成绝对形式再比前缀，避免相对路径写法绕过判定。
     */
    public static boolean isSpillReadback(String toolName, Map<String, Object> args, Path spillDir) {
        if (!"ReadFile".equals(toolName) || args == null) {
            return false;
        }
        Object raw = args.get("file_path");
        if (!(raw instanceof String path) || path.isEmpty()) {
            return false;
        }
        try {
            String abs = Path.of(path).toAbsolutePath().normalize().toString();
            String spill = spillDir.toAbsolutePath().normalize().toString();
            return abs.startsWith(spill);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 单条溢写：把完整内容写盘，返回替换进历史的预览块文本。
     * 写盘失败则保留原文（消息照常定型，不会重试）。
     */
    public static String persistLargeResult(Path spillDir, String toolUseId, String content) {
        Path file = writeSpill(spillDir, toolUseId, content);
        if (file == null) {
            return content;
        }
        return buildSpillPreview(content, file);
    }

    /** 构造 {@code <persisted-output>} 预览块。相同输入产出逐字节相同的字符串。 */
    private static String buildSpillPreview(String content, Path path) {
        int sizeKB = content.length() / 1024;
        String preview = content.length() <= PREVIEW_CHARS
                ? content : content.substring(0, PREVIEW_CHARS);
        return "<persisted-output>\n"
                + "Full output saved to: " + path + " (size: ~" + sizeKB + "KB)\n"
                + "--- preview (first " + PREVIEW_CHARS + " chars) ---\n"
                + preview
                + "\n</persisted-output>";
    }

    /**
     * 聚合溢写：单条都没超标，但一轮并行调用的结果加起来超过 {@link #MESSAGE_AGGREGATE_LIMIT} 时，
     * 从最大的非豁免结果开始存盘。ToolResultBlock 是不可变的 record，所以处理方式是
     * 拷贝一份列表、按需替换元素后整体返回。
     *
     * <p>挑最大的是因为单位收益最高，用最少的替换次数把总量压下来。
     * 全是豁免项时接受超额；写盘失败的结果保留原文。
     */
    public static List<ToolResultBlock> apply(
            List<ToolResultBlock> results,
            Path spillDir,
            Set<String> exemptIds) {
        long total = 0;
        for (ToolResultBlock tr : results) {
            total += tr.content().length();
        }
        if (total <= MESSAGE_AGGREGATE_LIMIT) {
            return results;
        }

        List<ToolResultBlock> out = new ArrayList<>(results);
        // 按内容长度降序挑选：先溢写最大的，回到限额内需要动的条数最少。
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(
                (Integer i) -> out.get(i).content().length()).reversed());

        for (int idx : order) {
            if (total <= MESSAGE_AGGREGATE_LIMIT) {
                break;
            }
            ToolResultBlock tr = out.get(idx);
            if (exemptIds != null && exemptIds.contains(tr.toolUseId())) {
                continue;
            }
            String content = tr.content();
            if (content.length() <= PREVIEW_CHARS) {
                // 已经很小了，溢写它没有意义，跳过避免无谓落盘
                continue;
            }
            Path file = writeSpill(spillDir, tr.toolUseId(), content);
            if (file == null) {
                continue; // 写盘失败保留原文
            }
            String preview = buildSpillPreview(content, file);
            out.set(idx, new ToolResultBlock(tr.toolUseId(), preview, tr.isError(), tr.contentBlocks()));
            total -= (content.length() - preview.length());
        }
        return out;
    }

    /**
     * 原子写入：{@code CREATE_NEW} 等价 {@code O_EXCL}，实现独占创建。
     * 文件已存在捕获 {@link java.nio.file.FileAlreadyExistsException} 直接复用路径，不覆写。
     * 文件名用 tool_use_id 加 {@code .txt} 后缀，天然幂等；目录按会话隔离。
     */
    private static Path writeSpill(Path spillDir, String toolUseId, String content) {
        if (toolUseId == null || toolUseId.isEmpty()) {
            return null;
        }
        try {
            Files.createDirectories(spillDir);
            Path file = spillDir.resolve(toolUseId + ".txt");
            try {
                Files.writeString(file, content,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // 同一 tool_use_id 已落盘过，直接复用，不覆写
            }
            return file;
        } catch (IOException e) {
            return null;
        }
    }
}
