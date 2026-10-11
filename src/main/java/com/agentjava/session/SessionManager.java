package com.agentjava.session;

import com.agentjava.conversation.ConversationManager;
import com.agentjava.conversation.Message;
import com.agentjava.conversation.ToolResultBlock;
import com.agentjava.conversation.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 会话持久化：JSONL 格式的会话存档与恢复。
 *
 * <p><b>为什么选 JSONL</b>：追加写入是 O(1)，不必把整份会话读出来重写一遍；
 * 一行一条记录，写到一半进程崩溃也只可能坏掉最后一行，前面的历史完好无损；
 * 恢复时可以增量地逐行加载。代价是不支持随机修改，但会话本来就是只追加的。
 *
 * <p><b>本类只管如实存、如实读，不做配对修复</b>。工具调用链的补位
 * （悬空 tool_use 补一条错误结果、孤儿 tool_result 丢弃）放在对话层
 * {@link ConversationManager#fixToolCallChain()} 做——发请求前统一兜一层，
 * 会话恢复只是它要兜住的来路之一。
 */
public final class SessionManager {

    /** 会话目录相对于工作目录的位置。 */
    private static final String SESSIONS_DIR = ".agentjava/sessions";

    /** 会话过期阈值：超过 30 天没动过的会话文件会被清理掉。 */
    public static final int EXPIRY_DAYS = 30;

    /** Session ID 的时间戳部分格式。 */
    private static final DateTimeFormatter ID_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionManager() {
    }

    // ───────────────────────── 路径与 ID ─────────────────────────

    /** 会话存档目录：{@code <workDir>/.agentjava/sessions}。 */
    public static Path sessionsDir(String workDir) {
        return Path.of(workDir, SESSIONS_DIR);
    }

    /**
     * 生成会话 ID：{@code YYYYMMDD-HHMMSS-xxxx}，时间戳加 4 位十六进制随机后缀。
     *
     * <p>随机部分用 {@link SecureRandom} 生成 2 字节。带上随机后缀是为了避免
     * 同一秒创建多个会话时撞名；极端情况下 SecureRandom 不可用时兜底取纳秒低 16 位。
     */
    public static String newId() {
        String timestamp = LocalDateTime.now().format(ID_TS);
        byte[] randomBytes = new byte[2];
        try {
            SecureRandom.getInstanceStrong().nextBytes(randomBytes);
        } catch (NoSuchAlgorithmException e) {
            int fallback = (int) (System.nanoTime() & 0xFFFF);
            return "%s-%04x".formatted(timestamp, fallback);
        }
        return "%s-%s".formatted(timestamp, HexFormat.of().formatHex(randomBytes));
    }

    // ───────────────────────── 写入 ─────────────────────────

    /**
     * 追加一条记录。每次写入都重新打开文件追加、写完即关。
     *
     * <p>用 {@link LinkedHashMap} 手工拼 JSON 而不是直接把 {@link SessionMessage}
     * 交给 Jackson 序列化，是为了精确控制哪些键出现：{@code type} 和两个工具列表
     * 为空时整个键不写入，纯文本消息落盘还是干干净净的三个字段。
     * 顺带还让字段顺序固定下来，落盘的行读起来不会每次都乱序。
     */
    private static void saveRecord(String workDir, String sessionId, String role, String type,
                                   String content, List<ToolUseRecord> toolUses,
                                   List<ToolResultRecord> toolResults) {
        try {
            Path baseDir = sessionsDir(workDir);
            Files.createDirectories(baseDir);
            Path file = baseDir.resolve(sessionId + ".jsonl");
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("role", role);
            if (type != null && !type.isEmpty()) {
                line.put("type", type);
            }
            line.put("content", content);
            line.put("ts", Instant.now().getEpochSecond());
            if (toolUses != null && !toolUses.isEmpty()) {
                line.put("tool_uses", toolUses);
            }
            if (toolResults != null && !toolResults.isEmpty()) {
                line.put("tool_results", toolResults);
            }
            String json = MAPPER.writeValueAsString(line) + "\n";
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 会话存档失败不能影响主流程：宁可丢这一段历史，也不让对话中断
        }
    }

    /**
     * 把内存里的一条对话消息连同它携带的工具块一起落盘。
     *
     * <p>有一样东西刻意不存，就是思考块——它带的 signature 只在同一轮工具循环里
     * 回传给模型时有意义，跨会话恢复用不上。
     */
    public static void saveConversationMessage(String workDir, String sessionId, Message msg) {
        if (msg == null || msg.getStatus() == Message.Status.ERROR) {
            return;
        }
        saveRecord(workDir, sessionId, msg.getRole().apiValue(), null, msg.getContent(),
                toolUseRecords(msg), toolResultRecords(msg));
    }

    /**
     * 写入压缩边界记录。
     *
     * <p>摘要和保留的尾部原文整体序列化成 JSON 塞进 {@code content}，这样
     * {@link #loadSession} 无需为边界单开一套字段。
     */
    public static void saveCompactBoundary(String workDir, String sessionId,
                                           String summary, List<KeepMessage> keep) {
        try {
            String payload = MAPPER.writeValueAsString(new CompactBoundary(summary, keep));
            saveRecord(workDir, sessionId, "user", SessionMessage.TYPE_COMPACT_BOUNDARY,
                    payload, List.of(), List.of());
        } catch (IOException e) {
            // 边界写不进去最多是下次恢复时少一层压缩优化，不影响可用性
        }
    }

    private static List<ToolUseRecord> toolUseRecords(Message msg) {
        if (msg.getToolUses() == null || msg.getToolUses().isEmpty()) {
            return List.of();
        }
        List<ToolUseRecord> out = new ArrayList<>(msg.getToolUses().size());
        for (ToolUseBlock tu : msg.getToolUses()) {
            out.add(new ToolUseRecord(tu.toolUseId(), tu.toolName(), tu.arguments()));
        }
        return out;
    }

    private static List<ToolResultRecord> toolResultRecords(Message msg) {
        if (msg.getToolResults() == null || msg.getToolResults().isEmpty()) {
            return List.of();
        }
        List<ToolResultRecord> out = new ArrayList<>(msg.getToolResults().size());
        for (ToolResultBlock tr : msg.getToolResults()) {
            out.add(new ToolResultRecord(tr.toolUseId(), tr.content(), tr.isError()));
        }
        return out;
    }

    /** 把压缩后保留的尾部原文转成 {@link KeepMessage} 列表。 */
    public static List<KeepMessage> keepMessagesFrom(List<Message> tail) {
        var out = new ArrayList<KeepMessage>(tail.size());
        for (Message m : tail) {
            if (m == null || m.getStatus() == Message.Status.ERROR) {
                continue;
            }
            out.add(new KeepMessage(m.getRole().apiValue(), m.getContent(),
                    toolUseRecords(m), toolResultRecords(m)));
        }
        return out;
    }

    // ───────────────────────── 读取 ─────────────────────────

    /**
     * 逐行读取会话存档。
     *
     * <p>解析失败的行静默跳过，坏行的 try-catch 套在循环体内层——
     * 这样一行读坏不会把整个循环带出去。
     *
     * <p>空记录的判定是这里最容易写错的地方：只带工具结果的那条消息本身没有正文，
     * {@code content} 就是空字符串。如果只看 content 是否为空来过滤，整条工具往返
     * 都会在恢复时被悄悄丢掉，模型拿到的历史里会出现一个没有结果的调用。
     * 所以判空条件是三个都空才算空：正文为空、没有工具调用、也没有工具结果。
     */
    public static List<SessionMessage> loadSession(String workDir, String sessionId) {
        var messages = new ArrayList<SessionMessage>();
        Path file = sessionsDir(workDir).resolve(sessionId + ".jsonl");
        if (!Files.isRegularFile(file)) {
            return messages;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> map = MAPPER.readValue(line, Map.class);
                    String role = (String) map.get("role");
                    String type = (String) map.get("type");
                    String content = (String) map.get("content");
                    // ts 用 instanceof Number n 收敛：Jackson 可能给 Integer 也可能给 Long，
                    // 直接强转 (Long) 会在小数值上抛 ClassCastException
                    long ts = map.get("ts") instanceof Number n ? n.longValue() : 0L;
                    List<ToolUseRecord> toolUses = readToolUses(map.get("tool_uses"));
                    List<ToolResultRecord> toolResults = readToolResults(map.get("tool_results"));
                    boolean empty = (content == null || content.isEmpty())
                            && toolUses.isEmpty() && toolResults.isEmpty();
                    if (!empty) {
                        messages.add(new SessionMessage(role, type,
                                content == null ? "" : content, ts, toolUses, toolResults));
                    }
                } catch (IOException e) {
                    // 跳过坏行
                }
            }
        } catch (IOException e) {
            // 文件读不了就当空会话
        }
        return messages;
    }

    private static List<ToolUseRecord> readToolUses(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        try {
            return MAPPER.convertValue(list, new TypeReference<List<ToolUseRecord>>() {
            });
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private static List<ToolResultRecord> readToolResults(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        try {
            return MAPPER.convertValue(list, new TypeReference<List<ToolResultRecord>>() {
            });
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    // ───────────────────────── 压缩感知重建 ─────────────────────────

    /** 压缩边界的内容载体。 */
    public record CompactBoundary(String summary, List<KeepMessage> keep) {
        public CompactBoundary(String summary, List<KeepMessage> keep) {
            this.summary = summary == null ? "" : summary;
            this.keep = keep == null ? List.of() : keep;
        }
    }

    /** 边界扫描结果：{@code found} 为 false 时 index 为 -1、boundary 为 null。 */
    public record BoundaryScan(boolean found, int index, CompactBoundary boundary) {
    }

    /** 从后往前找最后一条压缩边界记录。 */
    public static BoundaryScan findLastCompactBoundary(List<SessionMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            SessionMessage m = messages.get(i);
            if (m.isCompactBoundary()) {
                try {
                    CompactBoundary b = MAPPER.readValue(m.content(), CompactBoundary.class);
                    return new BoundaryScan(true, i, b);
                } catch (IOException e) {
                    // 边界本身坏了就当它没有，继续往前找
                }
            }
        }
        return new BoundaryScan(false, -1, null);
    }

    /**
     * 用压缩感知的方式重建对话：摘要 → 保留的尾部原文 → 边界之后追加的记录。
     *
     * <p>边界之前的那些逐条记录全部丢弃——它们已经被摘要替代了。没有边界时走
     * {@link #replay(List)} 全量回放，兼容没有压缩过的会话。
     */
    public static ConversationManager rebuildConversation(List<SessionMessage> messages) {
        BoundaryScan scan = findLastCompactBoundary(messages);
        if (!scan.found()) {
            return replay(messages);
        }
        var conv = new ConversationManager();
        conv.addUserMessage(scan.boundary().summary());
        appendKeep(conv, scan.boundary().keep());
        appendSessionMessages(conv, messages.subList(scan.index() + 1, messages.size()));
        return conv;
    }

    /** 全量回放（无压缩边界时的兼容路径）。 */
    public static ConversationManager replay(List<SessionMessage> messages) {
        var conv = new ConversationManager();
        appendSessionMessages(conv, messages);
        return conv;
    }

    private static void appendKeep(ConversationManager conv, List<KeepMessage> keep) {
        for (KeepMessage k : keep) {
            boolean isAssistant = "assistant".equals(k.role());
            if (isAssistant) {
                appendOne(conv, true, k.content(), toToolUseBlocks(k.toolUses()), List.of());
            } else {
                appendOne(conv, false, k.content(), List.of(), toToolResultBlocks(k.toolResults()));
            }
        }
    }

    private static void appendSessionMessages(ConversationManager conv, List<SessionMessage> msgs) {
        for (SessionMessage m : msgs) {
            if (m.isCompactBoundary()) {
                continue;
            }
            boolean isAssistant = "assistant".equals(m.role());
            appendOne(conv, isAssistant, m.content(),
                    toToolUseBlocks(m.toolUses()), toToolResultBlocks(m.toolResults()));
        }
    }

    private static void appendOne(ConversationManager conv, boolean isAssistant, String content,
                                  List<ToolUseBlock> tus, List<ToolResultBlock> trs) {
        if (isAssistant) {
            if (!tus.isEmpty()) {
                conv.addAssistantMessageWithTools(content == null ? "" : content, tus);
            } else if (content != null && !content.isBlank()) {
                conv.addAssistantMessage(content);
            }
            return;
        }
        if (!trs.isEmpty()) {
            conv.addToolResultsMessage(trs);
        } else if (content != null && !content.isBlank()) {
            conv.addUserMessage(content);
        }
    }

    private static List<ToolUseBlock> toToolUseBlocks(List<ToolUseRecord> records) {
        var out = new ArrayList<ToolUseBlock>(records.size());
        for (ToolUseRecord r : records) {
            out.add(new ToolUseBlock(r.toolUseId(), r.toolName(), r.arguments()));
        }
        return out;
    }

    private static List<ToolResultBlock> toToolResultBlocks(List<ToolResultRecord> records) {
        var out = new ArrayList<ToolResultBlock>(records.size());
        for (ToolResultRecord r : records) {
            out.add(new ToolResultBlock(r.toolUseId(), r.content(), r.isError()));
        }
        return out;
    }

    // ───────────────────────── 列表与清理 ─────────────────────────

    /**
     * 列出全部会话，按最后修改时间倒序。
     *
     * <p>没有自动生成标题的逻辑——标题要等列表时才扫描，写入阶段不做额外计算。
     */
    public static List<SessionInfo> listSessions(String workDir) {
        var out = new ArrayList<SessionInfo>();
        Path dir = sessionsDir(workDir);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
        } catch (IOException e) {
            return out;
        }
        String branch = readGitBranch(workDir);
        for (Path f : files) {
            String id = f.getFileName().toString().replaceFirst("\\.jsonl$", "");
            out.add(new SessionInfo(id, firstUserMessage(f), countLines(f), sizeOf(f),
                    branch, mtimeOf(f)));
        }
        out.sort(Comparator.comparing(SessionInfo::modTime).reversed());
        return out;
    }

    /** 列出自 {@code sinceMillis} 以来有更新的会话 ID（记忆治理的会话门要用）。 */
    public static List<String> listSessionsSince(String workDir, long sinceMillis) {
        var out = new ArrayList<String>();
        for (SessionInfo s : listSessions(workDir)) {
            if (s.modTime().toEpochMilli() >= sinceMillis) {
                out.add(s.id());
            }
        }
        return out;
    }

    /** 删除指定会话的存档文件。 */
    public static boolean deleteSession(String workDir, String sessionId) {
        try {
            return Files.deleteIfExists(sessionsDir(workDir).resolve(sessionId + ".jsonl"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 清理过期会话：按文件最后修改时间判断是否超过 {@value #EXPIRY_DAYS} 天，超过的直接删除。
     *
     * <p>清理是尽力而为的：单个文件删除失败不影响其它文件的清理。
     *
     * @return 实际删掉的文件数
     */
    public static int cleanExpiredSessions(String workDir) {
        Path dir = sessionsDir(workDir);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - (long) EXPIRY_DAYS * 86_400_000L;
        int removed = 0;
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
        } catch (IOException e) {
            return 0;
        }
        for (Path p : files) {
            try {
                if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                    Files.deleteIfExists(p);
                    removed++;
                }
            } catch (IOException e) {
                // 单个失败不影响其余
            }
        }
        return removed;
    }

    // ───────────────────────── 元信息提取 ─────────────────────────

    /** 首条用户正文，截断到 80 字符作为标题预览。 */
    private static String firstUserMessage(Path file) {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> m = MAPPER.readValue(line, Map.class);
                    if ("user".equals(m.get("role")) && m.get("content") instanceof String s
                            && !s.isBlank()) {
                        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
                    }
                } catch (IOException e) {
                    // 坏行跳过，继续找下一行
                }
            }
        } catch (IOException e) {
            return "";
        }
        return "";
    }

    private static int countLines(Path file) {
        int n = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            while (r.readLine() != null) {
                n++;
            }
        } catch (IOException e) {
            return n;
        }
        return n;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static Instant mtimeOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    /** 读 {@code <workDir>/.git/HEAD} 得到分支名； detached HEAD 或读不到时返回空串。 */
    public static String readGitBranch(String workDir) {
        try {
            Path head = Path.of(workDir, ".git", "HEAD");
            if (!Files.isRegularFile(head)) {
                return "";
            }
            String raw = Files.readString(head, StandardCharsets.UTF_8).trim();
            if (raw.startsWith("ref: refs/heads/")) {
                return raw.substring("ref: refs/heads/".length());
            }
            return raw.length() > 7 ? raw.substring(0, 7) : raw;
        } catch (IOException e) {
            return "";
        }
    }
}
