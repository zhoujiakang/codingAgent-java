package com.agentjava.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.agentjava.conversation.ConversationManager;
import com.agentjava.llm.LlmClient;
import com.agentjava.llm.StreamEvent;

/**
 * 自动记忆管理器：双目录存储、MEMORY.md 索引、LLM 提取、新会话注入。
 *
 * <p><b>双目录分层</b>：type 字段决定路由方向。
 * {@code user}/{@code feedback} 跟人走，存到用户级目录 {@code ~/.agentjava/memory/}；
 * {@code project}/{@code reference} 跟项目走，存到 {@code <workDir>/.agentjava/memory/}。
 * 换项目时关于「我是谁、我习惯怎么写」的记忆还在，关于「这个项目怎么组织」的记忆留在原地。
 *
 * <p><b>索引只存指针</b>：MEMORY.md 里每行一条 {@code - [Title](file.md) — 描述}。
 * 每轮对话都要把它发给模型，所以索引大小是持续付出的 token 成本，必须封顶。
 */
public class MemoryManager {

    /** 项目级记忆目录相对路径。 */
    public static final String MEMORY_DIR = ".agentjava/memory";

    /** 索引行数上限。 */
    public static final int MAX_ENTRYPOINT_LINES = 200;

    /** 索引字节上限。 */
    public static final int MAX_ENTRYPOINT_BYTES = 25_000;

    /** 提取频率：每 N 轮 Loop 结束触发一次。当前每轮都提取。 */
    public static final int EXTRACTION_INTERVAL = 1;

    /** 提取时回看的最近消息条数。 */
    private static final int RECENT_WINDOW = 40;

    /** 对话太短时不值得提取。 */
    private static final int MIN_MESSAGES = 4;

    private static final Set<String> USER_TYPES = Set.of("user", "feedback");
    private static final Set<String> PROJECT_TYPES = Set.of("project", "reference");

    private final String workDir;
    private final Path projectMemDirPath;
    private final Path userMemDirPath;

    /** 已完成的 Loop 轮数，驱动 {@link #shouldExtract()}。 */
    private int turnCount = 0;

    public MemoryManager(String workDir) {
        this.workDir = workDir == null ? System.getProperty("user.dir") : workDir;
        this.projectMemDirPath = Path.of(this.workDir, MEMORY_DIR);
        String home = System.getProperty("user.home");
        this.userMemDirPath = Path.of(home == null ? "." : home, MEMORY_DIR);
        ensureDir(userMemDirPath);
        ensureDir(projectMemDirPath);
    }

    public Path userMemDir() {
        return userMemDirPath;
    }

    public Path projectMemDir() {
        return projectMemDirPath;
    }

    public String workDir() {
        return workDir;
    }

    // ───────────────────────── 第一层：项目指令 ─────────────────────────

    /** 加载项目指令（委托给 {@link InstructionLoader}）。 */
    public String loadInstructions() {
        return InstructionLoader.loadInstructions(workDir);
    }

    // ───────────────────────── 第三层：自动记忆 ─────────────────────────

    /**
     * 判断是否本轮该提取。
     *
     * <p>留着取模判断而不是直接返回 true，是把频率做成一个可调的旋钮：改一个常量就能
     * 降到每 3 轮或每 5 轮，调用方那边不用动。<b>注意这个方法有副作用</b>——
     * {@code turnCount++} 写在判断之前，一轮 Loop 里只能调一次，多调一次计数就多走一格。
     */
    public boolean shouldExtract() {
        turnCount++;
        return turnCount % EXTRACTION_INTERVAL == 0;
    }

    /**
     * 从最近一段对话里提取值得记住的信息，写入记忆文件与索引。
     *
     * <p>走的是「一次独立 LLM 调用 + 文本解析」这条路，而不是子 Agent：
     * 这里不需要读文件、改代码，给子 Agent 配一套工具集纯属浪费。
     *
     * <p>方法是<b>同步阻塞</b>的，返回时提取已经落盘；异步由调用侧的虚拟线程负责
     * （{@code Thread.startVirtualThread}）——提取绝大部分时间是在等网络返回，
     * 占着一个平台线程纯属浪费。
     */
    public void extract(LlmClient client, ConversationManager conv) {
        if (client == null || conv == null) {
            return;
        }
        List<com.agentjava.conversation.Message> messages = conv.getMessages();
        if (messages.size() < MIN_MESSAGES) {
            return;
        }

        int start = Math.max(0, messages.size() - RECENT_WINDOW);
        var sb = new StringBuilder();
        for (int i = start; i < messages.size(); i++) {
            var msg = messages.get(i);
            String content = msg.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            sb.append('[').append(msg.getRole().apiValue()).append("]: ")
                    .append(content).append('\n');
        }

        String manifest = scanExistingMemories();
        String manifestSection = manifest.isEmpty() ? "" :
                "\n\n## Existing memory files\n\n" + manifest +
                        "\n\nCheck this list before creating — update an existing file "
                        + "rather than creating a duplicate.";

        ConversationManager extractConv = new ConversationManager();
        extractConv.addUserMessage(
                "Analyze the conversation below and extract memories worth keeping "
                        + "for future sessions.\n"
                        + "For each memory, output in this exact format:\n"
                        + "MEMORY_NAME: <kebab-case-name>\n"
                        + "MEMORY_TYPE: <user|feedback|project|reference>\n"
                        + "MEMORY_DESC: <one-line description>\n"
                        + "MEMORY_BODY: <single-line content>\n"
                        + "---\n\n"
                        + "Types:\n"
                        + "- user/feedback → save to " + userMemDirPath + "\n"
                        + "- project/reference → save to " + projectMemDirPath + "\n\n"
                        + "Keep each field on a single line. "
                        + "If nothing is worth saving, output NONE."
                        + manifestSection + "\n\nConversation:\n" + sb
        );

        String output = collect(client.stream(extractConv, null));
        persistExtracted(output);
    }

    /** 同步收干一次流式调用，返回拼接后的纯文本。失败或超时返回已收到的部分。 */
    private static String collect(BlockingQueue<StreamEvent> queue) {
        var result = new StringBuilder();
        while (true) {
            StreamEvent event;
            try {
                event = queue.poll(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result.toString();
            }
            if (event == null) {
                break;
            }
            if (event instanceof StreamEvent.TextDelta td) {
                result.append(td.text());
            } else if (event instanceof StreamEvent.StreamEnd) {
                break;
            } else if (event instanceof StreamEvent.Error) {
                break;
            }
        }
        return result.toString();
    }

    /** 解析 LLM 输出的若干条记忆并落盘。 */
    void persistExtracted(String rawOutput) {
        String output = rawOutput == null ? "" : rawOutput.trim();
        if (output.isEmpty() || output.equals("NONE") || !output.contains("MEMORY_NAME:")) {
            return;
        }
        for (String block : output.split("---")) {
            if (!block.contains("MEMORY_NAME:")) {
                continue;
            }
            String name = extractField(block, "MEMORY_NAME");
            String type = extractField(block, "MEMORY_TYPE");
            String desc = extractField(block, "MEMORY_DESC");
            String body = extractField(block, "MEMORY_BODY");
            if (name.isEmpty() || body.isEmpty()) {
                continue;
            }
            if (!USER_TYPES.contains(type) && !PROJECT_TYPES.contains(type)) {
                type = "reference";
            }
            Path targetDir = USER_TYPES.contains(type) ? userMemDirPath : projectMemDirPath;
            writeMemoryFile(targetDir, name, type, desc, body);
        }
    }

    /** 用正则 {@code FIELD:\s*(.+?)(?:\n|$)} 做单行匹配。 */
    static String extractField(String block, String field) {
        Matcher m = Pattern.compile(Pattern.quote(field) + ":\\s*(.+?)(?:\\n|$)")
                .matcher(block);
        return m.find() ? m.group(1).trim() : "";
    }

    /** 已有记忆的清单，用于在提取 prompt 里做去重提示。 */
    public String scanExistingMemories() {
        var sb = new StringBuilder();
        for (MemoryFile f : loadAll()) {
            String type = f.type().isBlank() ? "unknown" : f.type();
            sb.append("- ").append(type).append(": ").append(f.description()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 写入一条记忆文件，并在 MEMORY.md 索引里追加指针。
     *
     * <p>索引更新是追加式的：检查一下 MEMORY.md 里有没有这个文件名，没有就追加一行。
     * 如果同名文件已存在（LLM 更新了一条已有记忆），文件内容会被覆盖，
     * 但索引不会重复添加。
     */
    private void writeMemoryFile(Path dir, String name, String type,
                                 String description, String body) {
        ensureDir(dir);
        String filename = name + ".md";
        Path filePath = dir.resolve(filename);
        String fileContent = "---\nname: %s\ndescription: %s\n".formatted(name, description)
                + "metadata:\n  type: %s\n---\n\n%s\n".formatted(type, body);
        try {
            Files.writeString(filePath, fileContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        Path entrypoint = dir.resolve(MemoryScanner.ENTRYPOINT_NAME);
        String pointer = "- [%s](%s) — %s\n".formatted(name, filename, description);
        try {
            String existing = Files.exists(entrypoint)
                    ? Files.readString(entrypoint, StandardCharsets.UTF_8) : "";
            if (!existing.contains(filename)) {
                Files.writeString(entrypoint, existing + pointer, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            // 索引写不进去不影响记忆文件本身
        }
    }

    /** 加载双目录的全部记忆文件，用户级在前、项目级在后。 */
    public List<MemoryFile> loadAll() {
        var out = new ArrayList<MemoryFile>();
        out.addAll(MemoryScanner.loadDir(userMemDirPath));
        out.addAll(MemoryScanner.loadDir(projectMemDirPath));
        return out;
    }

    /** 只加载用户级记忆（{@code ~/.agentjava/memory}）。分组展示与分别计数都靠它。 */
    public List<MemoryFile> loadUserMemories() {
        return MemoryScanner.loadDir(userMemDirPath);
    }

    /** 只加载项目级记忆（{@code <workDir>/.agentjava/memory}）。 */
    public List<MemoryFile> loadProjectMemories() {
        return MemoryScanner.loadDir(projectMemDirPath);
    }

    /**
     * 清空双目录：删掉全部 {@code .md}（含两份 MEMORY.md 索引），返回删除条数。
     *
     * <p>留着目录不删：目录是在构造时顺手创建的，删了下次还得建；而空目录本身
     * 没有任何副作用。只认 {@code .md}，避免把别人放进来的临时文件一并扫掉。
     */
    public int clearAll() {
        int removed = 0;
        for (Path dir : List.of(userMemDirPath, projectMemDirPath)) {
            removed += deleteMarkdownFiles(dir);
        }
        return removed;
    }

    private static int deleteMarkdownFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        int removed = 0;
        try (var entries = Files.list(dir)) {
            for (Path p : entries.toList()) {
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md")) {
                    try {
                        Files.deleteIfExists(p);
                        removed++;
                    } catch (IOException e) {
                        // 单个文件删不掉不影响其余文件
                    }
                }
            }
        } catch (IOException e) {
            // 目录扫不动就算了（权限、并发删除），返回已删条数
        }
        return removed;
    }

    // ───────────────────────── 记忆注入 ─────────────────────────

    /**
     * 构造注入用的 system-reminder：读取双目录的 MEMORY.md 索引。
     *
     * <p>注入的是<b>索引</b>而不是全文：Agent 启动时先知道有哪些记忆可用，
     * 具体内容等到用得上的时候再通过记忆召回按需读取。
     */
    public String buildSystemReminder() {
        ensureDir(userMemDirPath);
        ensureDir(projectMemDirPath);
        var sb = new StringBuilder();
        sb.append("# auto memory\n\n");
        appendEntrypoint(sb, "User-level", userMemDirPath);
        sb.append("\n\n");
        appendEntrypoint(sb, "Project-level", projectMemDirPath);
        return sb.toString();
    }

    private void appendEntrypoint(StringBuilder sb, String label, Path dir) {
        Path entrypoint = dir.resolve(MemoryScanner.ENTRYPOINT_NAME);
        String content;
        try {
            content = Files.isRegularFile(entrypoint)
                    ? Files.readString(entrypoint, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            content = "";
        }
        sb.append("## ").append(label).append(" (").append(dir).append(")\n\n");
        if (content.isBlank()) {
            sb.append("(no memories yet)\n");
        } else {
            sb.append(truncateEntrypointContent(content));
        }
    }

    /** 仅在对话为空（新会话）时注入记忆索引，并补一条助手确认凑成合法的 user/assistant 交替。 */
    public void injectMemories(ConversationManager conv) {
        if (conv == null || !conv.getMessages().isEmpty()) {
            return;
        }
        String reminder = buildSystemReminder();
        if (reminder.isBlank()) {
            return;
        }
        conv.addSystemReminder(reminder);
        conv.addAssistantMessage("Understood, I'll keep this context in mind.");
    }

    // ───────────────────────── 索引截断 ─────────────────────────

    /**
     * 把索引压到行数和字节双上限之内。
     *
     * <p>这不是防御异常输入，而是给常态开销封顶：索引每轮对话都要重新发给模型，
     * 多一行就是一份持续付出的 token 成本。
     *
     * <p>先按行截断，再按字节截断——因为单行可以很长，200 行以内照样能堆到上百 KB。
     * 超限时末尾追加一条 WARNING，这句话是给模型看的：它需要知道自己拿到的
     * 索引是残缺的，否则会以为某条记忆不存在，转头又建一条重复的。
     */
    static String truncateEntrypointContent(String content) {
        String trimmed = content == null ? "" : content.strip();
        String[] lines = trimmed.split("\n", -1);
        boolean overLines = lines.length > MAX_ENTRYPOINT_LINES;
        String result = trimmed;
        if (overLines) {
            result = String.join("\n",
                    java.util.Arrays.copyOfRange(lines, 0, MAX_ENTRYPOINT_LINES));
        }
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        boolean overBytes = bytes.length > MAX_ENTRYPOINT_BYTES;
        if (overBytes) {
            result = cutToBytes(bytes, MAX_ENTRYPOINT_BYTES);
        }
        if (!overLines && !overBytes) {
            return result;
        }
        StringBuilder sb = new StringBuilder(result);
        sb.append("\n\n<!-- WARNING: memory index truncated (");
        if (overLines) {
            sb.append("超过 ").append(MAX_ENTRYPOINT_LINES).append(" 行");
        }
        if (overLines && overBytes) {
            sb.append("，且");
        }
        if (overBytes) {
            sb.append("超过 ").append(MAX_ENTRYPOINT_BYTES).append(" 字节");
        }
        sb.append(")。部分记忆未列出，不代表它们不存在。 -->");
        return sb.toString();
    }

    /**
     * 按字节上限切断。
     *
     * <p>必须先在字节层面执行：如果图省事写成 {@code result.substring(0, limit)}，
     * 截的是<b>字符</b>不是字节，一个汉字在 UTF-8 里占三字节，
     * 中文索引正是常态，25000 的上限实际会放过七万多字节。
     *
     * <p>优先切在上限之前的最后一个换行处，留下的都是完整条目。UTF-8 有个很好用的性质：
     * ASCII 字节永远不会出现在多字节字符的内部，所以直接在字节数组里找 {@code '\n'} 是安全的。
     * 整段没有换行时只能按字节硬切，这时回退到字符边界：UTF-8 的后续字节高两位固定是 {@code 10}，
     * 从截断点往前跳过这些字节，落到的就是某个字符的起始位置。
     */
    static String cutToBytes(byte[] bytes, int limit) {
        int end = Math.min(limit, bytes.length);
        for (int i = end - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return new String(bytes, 0, i, StandardCharsets.UTF_8);
            }
        }
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    // ───────────────────────── 小工具 ─────────────────────────

    static void ensureDir(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // 目录建不出来不影响后续读写尝试
        }
    }
}
