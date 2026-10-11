package com.agentjava.history;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 输入历史：把用户输入持久化下来，支持上下键翻历史。
 *
 * <p><b>放在用户 home 目录下而不是项目目录</b>：输入历史跟人走不跟项目走，
 * 而且它是纯粹的本地行为数据，不该污染项目仓库（也不会被误提交）。
 *
 * <p>写入策略是<b>全量重写</b>而不是追加：历史有容量上限，追加就必须处理淘汰，
 * 而 200 条 × 平均几十字节的全量重写代价可以忽略，换来的是文件格式始终正确、
 * 不需要清理逻辑。
 */
public class HistoryStore {

    /** 容量上限，超过从头部（最老的）截掉多余的。 */
    public static final int MAX_ENTRIES = 200;

    private static final String FILE_NAME = "prompt_history.jsonl";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path path;
    private final List<String> entries = new ArrayList<>();

    public HistoryStore() {
        this(defaultPath());
    }

    /** 可注入文件路径，方便测试隔离。 */
    public HistoryStore(Path path) {
        this.path = path;
        load();
    }

    public static Path defaultPath() {
        String home = System.getProperty("user.home");
        return Path.of(home == null ? "." : home, ".agentjava", FILE_NAME);
    }

    public Path path() {
        return path;
    }

    /** 读盘重建列表；文件不存在或整行读不出.json object 的部分跳过。 */
    public void load() {
        entries.clear();
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    var node = MAPPER.readTree(line);
                    String text = node.path("text").asText("");
                    if (!text.isEmpty()) {
                        entries.add(text);
                    }
                } catch (IOException e) {
                    // 坏行跳过
                }
            }
        } catch (IOException e) {
            // 读不了就当空历史
        }
    }

    /**
     * 追加一条输入。
     *
     * <p>连续去重：和最后一条相同就不重复记录。翻历史时连着三条一模一样的命令，
     * 体验上只是在浪费按键次数。
     */
    public void append(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!entries.isEmpty() && entries.getLast().equals(text)) {
            return;
        }
        entries.add(text);
        if (entries.size() > MAX_ENTRIES) {
            int excess = entries.size() - MAX_ENTRIES;
            entries.subList(0, excess).clear();
        }
        writeToDisk();
    }

    /** 获取快照（防御性拷贝，避免调用方改到内部列表）。 */
    public List<String> getEntries() {
        return List.copyOf(entries);
    }

    /** 取最近 n 条，按时间顺序（最老在前）。 */
    public List<String> recent(int n) {
        if (n <= 0 || entries.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, entries.size() - n);
        return List.copyOf(entries.subList(from, entries.size()));
    }

    public int size() {
        return entries.size();
    }

    private void writeToDisk() {
        if (path == null) {
            return;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var sb = new StringBuilder();
            long now = System.currentTimeMillis() / 1000;
            for (String text : entries) {
                sb.append(MAPPER.writeValueAsString(
                        java.util.Map.of("text", text, "ts", now))).append('\n');
            }
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // 历史写不进去不影响主流程
        }
    }
}
