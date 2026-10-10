package com.agentjava.tui;

import com.agentjava.config.ProviderConfig;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.conversation.ToolResultBlock;
import com.agentjava.conversation.ToolUseBlock;
import com.agentjava.llm.LlmClient;
import com.agentjava.llm.StreamEvent;
import com.agentjava.prompt.PromptBuilder;
import com.agentjava.tool.ToolExecutor;
import com.agentjava.tool.ToolRegistry;
import com.agentjava.tui.tea.Ansi;
import com.agentjava.tui.tea.Command;
import com.agentjava.tui.tea.KeyPressMessage;
import com.agentjava.tui.tea.Message;
import com.agentjava.tui.tea.Model;
import com.agentjava.tui.tea.Program;
import com.agentjava.tui.tea.UpdateResult;
import com.agentjava.tui.tea.WindowSizeMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * 主 TUI 模型。
 *
 * 这是交互中枢：状态机、输入缓冲、流式缓冲、spinner、渲染都在这一层。
 * 它自己不读键盘也不写终端——读键盘交给 Program，写终端通过返回 Command 委派，
 * 所以整个 update() 都是纯粹的状态变换。
 *
 * 一轮对话的节奏：
 * 用户输入 → 加入历史 → 带完整历史调 API → 片段进缓冲区并实时渲染
 * → 本轮结束，缓冲区落成一条消息 → 等待下一轮输入。
 */
public class AgentModel implements Model {

    public static final String APP_NAME = "AgentJava";
    public static final String VERSION = "0.1.0";

    /** 退出命令。 */
    public static final String EXIT_COMMAND = "/exit";

    private final List<ProviderConfig> providers;
    private final String workingDir;
    private final String systemPrompt;
    private final ConversationManager conversation = new ConversationManager();

    /** 工具注册中心：六个内置工具 + 共享的文件状态缓存。 */
    private final ToolRegistry registry = ToolRegistry.createDefault();

    /** 本轮流式期间累积的工具调用（模型一个回合可能发起多个）。 */
    private final List<ToolUseBlock> pendingToolUses = new ArrayList<>();
    private String currentToolId;
    private String currentToolName;
    private final StringBuilder currentToolJson = new StringBuilder();

    private Program program;
    private AppState state;
    private int selectedIndex = 0;
    private ProviderConfig activeProvider;
    private LlmClient llmClient;

    /** 输入缓冲：Alt+Enter / Ctrl+J 插入换行，Enter 提交。 */
    private final StringBuilder input = new StringBuilder();

    /** 流式缓冲：片段实时进这里，本轮结束才落成一条历史消息。 */
    private final StringBuilder streamBuf = new StringBuilder();
    private boolean streaming = false;
    private long requestStartMillis = 0;
    private String spinnerVerb = SpinnerVerbs.random();

    /** 上一轮的统计信息，用于状态栏展示。 */
    private long lastElapsedMillis = 0;
    private int lastInputTokens = 0;
    private int lastOutputTokens = 0;
    private boolean usageAvailable = false;

    private int width = 80;
    private int height = 24;
    private boolean bannerPrinted = false;

    public AgentModel(List<ProviderConfig> providers, String workingDir) {
        this.providers = List.copyOf(providers);
        this.workingDir = workingDir == null ? System.getProperty("user.dir") : workingDir;
        this.systemPrompt = PromptBuilder.buildSystemPrompt(this.workingDir);
        // 只有一个 provider 时直接进入对话，多个时才让用户选（F2）
        if (this.providers.size() == 1) {
            this.state = AppState.CHAT;
            activateProvider(0);
        } else {
            this.state = AppState.PROVIDER_SELECT;
        }
    }

    /** Program 建好之后回灌进来，流式线程要靠它把事件投回事件循环。 */
    public void setProgram(Program program) {
        this.program = program;
    }

    // ───────────────────────── Model 接口实现 ─────────────────────────

    @Override
    public Command init() {
        // 启动横幅等拿到窗口尺寸后再输出，所以这里没有副作用
        return Command.none();
    }

    @Override
    public UpdateResult<? extends Model> update(Message msg) {
        if (msg instanceof WindowSizeMessage ws) {
            width = ws.width();
            height = ws.height();
            if (!bannerPrinted) {
                bannerPrinted = true;
                return UpdateResult.of(this, Command.println(renderBanner() + "\n" + renderReadyLine()));
            }
            return UpdateResult.of(this);
        }
        if (msg instanceof KeyPressMessage key) {
            return UpdateResult.of(this, handleKey(key));
        }
        if (msg instanceof StreamEventMessage sem) {
            return UpdateResult.of(this, handleStreamEvent(sem.event()));
        }
        return UpdateResult.of(this);
    }

    @Override
    public String view() {
        return state == AppState.PROVIDER_SELECT ? viewProviderSelect() : viewChat();
    }

    // ───────────────────────── 事件处理 ─────────────────────────

    private Command handleKey(KeyPressMessage key) {
        // Ctrl+C 在任何状态下都能安全退出（F10）
        if ("ctrl+c".equals(key.key()) || "ctrl+d".equals(key.key())) {
            return Command.quit();
        }
        if (state == AppState.PROVIDER_SELECT) {
            return handleProviderSelectKey(key);
        }
        return handleChatKey(key);
    }

    private Command handleProviderSelectKey(KeyPressMessage key) {
        switch (key.key()) {
            case "up" -> selectedIndex = Math.max(0, selectedIndex - 1);
            case "down" -> selectedIndex = Math.min(providers.size() - 1, selectedIndex + 1);
            case "enter" -> {
                activateProvider(selectedIndex);
                state = AppState.CHAT;
            }
            default -> {
                // 其他按键在选择界面里没有意义
            }
        }
        return Command.none();
    }

    private Command handleChatKey(KeyPressMessage key) {
        // 流式期间不接受新的提交，也不改输入缓冲（F9）
        if (streaming) {
            return Command.none();
        }
        switch (key.key()) {
            case "enter" -> {
                return submit();
            }
            // 多行编辑：Alt+Enter 与 Ctrl+J 都支持，不同终端发的序列不一样
            case "alt+enter", "ctrl+j" -> input.append('\n');
            case "backspace" -> {
                if (input.length() > 0) {
                    input.deleteCharAt(input.length() - 1);
                }
            }
            case "delete" -> input.setLength(0);
            case "ctrl+u" -> input.setLength(0);
            case "rune" -> input.append(key.rune());
            default -> {
                // 方向键等暂不支持（历史导航留待后续）
            }
        }
        return Command.none();
    }

    /** 提交一轮对话。 */
    private Command submit() {
        String text = input.toString();
        input.setLength(0);
        if (text.isBlank()) {
            return Command.none();
        }
        String trimmed = text.trim();
        if (EXIT_COMMAND.equals(trimmed)) {
            return Command.quit();
        }

        // 用户输入先写进 scrollback，再进历史：
        // 这样即使请求失败，用户也能看到自己问了什么
        Command echo = Command.println(Styles.PROMPT.render("❯ ") + Styles.USER.render(text));
        conversation.addUserMessage(text);

        if (llmClient == null) {
            return Command.batch(echo, Command.println(Styles.ERROR.render("✗ 还没有可用的 provider。")));
        }

        streaming = true;
        streamBuf.setLength(0);
        pendingToolUses.clear();
        currentToolId = null;
        currentToolName = null;
        currentToolJson.setLength(0);
        spinnerVerb = SpinnerVerbs.random();
        requestStartMillis = System.currentTimeMillis();

        BlockingQueue<StreamEvent> queue = llmClient.stream(
                conversation, registry.getAllSchemas(activeProvider.normalizedProtocol()));
        Thread.startVirtualThread(() -> pumpStream(queue));
        return echo;
    }

    /** 把流式事件逐个投回事件循环；遇到终止事件就收工。 */
    private void pumpStream(BlockingQueue<StreamEvent> queue) {
        try {
            while (true) {
                StreamEvent event = queue.take();
                if (program != null) {
                    program.send(new StreamEventMessage(event));
                }
                if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Command handleStreamEvent(StreamEvent event) {
        return switch (event) {
            // 正文增量进缓冲区，界面立刻就能看到（F8 前半段）
            case StreamEvent.TextDelta delta -> {
                streamBuf.append(delta.text());
                yield Command.none();
            }
            // 思考增量接收即丢弃，绝不渲染，也不混入正文（F5 / AC5）
            case StreamEvent.ThinkingDelta ignored -> Command.none();
            // 工具调用开始：记下 id / 名称，准备累积参数碎片
            case StreamEvent.ToolCallStart s -> {
                currentToolId = s.toolId();
                currentToolName = s.toolName();
                currentToolJson.setLength(0);
                yield Command.none();
            }
            // 工具参数 JSON 碎片：仅累积，真正的解析在 ToolCallComplete
            case StreamEvent.ToolCallDelta d -> {
                currentToolJson.append(d.text());
                yield Command.none();
            }
            // 单个工具参数完整解析完毕，登记一次工具调用
            case StreamEvent.ToolCallComplete c -> {
                pendingToolUses.add(new ToolUseBlock(c.toolId(), c.toolName(), c.arguments()));
                yield Command.none();
            }
            case StreamEvent.StreamEnd end -> finishTurnOrContinue(streamBuf.toString(), end);
            case StreamEvent.Error error -> failTurn(error.message());
        };
    }

    /**
     * 本轮结束：若模型发起了工具调用，就执行并把结果回传，再发起一轮（单步）；
     * 否则按普通回复落成一条消息。
     */
    private Command finishTurnOrContinue(String fullText, StreamEvent.StreamEnd end) {
        if (!pendingToolUses.isEmpty()) {
            var uses = new ArrayList<>(pendingToolUses);
            // 把"模型决定调工具"这一回合落进历史：正文 + tool_use 块
            conversation.addAssistantMessageWithTools(fullText, uses);
            // 真正执行工具，拿到 tool_result
            var results = ToolExecutor.execute(registry, uses);
            conversation.addToolResultsMessage(results);

            // 先把工具调用与结果摘要打到 scrollback，让用户看到"动手"了什么
            var cmds = new ArrayList<Command>();
            for (int i = 0; i < uses.size(); i++) {
                ToolUseBlock use = uses.get(i);
                ToolResultBlock res = results.get(i);
                cmds.add(Command.println(Styles.TOOL.render("⏺ " + use.toolName() + " " + previewArgs(use.arguments()))));
                cmds.add(Command.println(Styles.MUTED.render("  ↳ " + previewResult(res.content()))));
            }

            // 清零本轮工具状态，准备下一轮（模型基于工具结果作答）
            pendingToolUses.clear();
            currentToolId = null;
            currentToolName = null;
            currentToolJson.setLength(0);
            streamBuf.setLength(0);

            // 再发起一轮：把工具结果喂回模型，拿最终回复
            BlockingQueue<StreamEvent> queue = llmClient.stream(
                    conversation, registry.getAllSchemas(activeProvider.normalizedProtocol()));
            Thread.startVirtualThread(() -> pumpStream(queue));
            return Command.batch(cmds.toArray(new Command[0]));
        }
        return finishTurn(fullText, end.inputTokens(), end.outputTokens());
    }

    /** 本轮正常结束：缓冲区落成一条完整消息，整段用 Markdown 定型渲染。 */
    private Command finishTurn(String fullText, int inputTokens, int outputTokens) {
        long elapsed = System.currentTimeMillis() - requestStartMillis;
        streaming = false;
        streamBuf.setLength(0);

        lastElapsedMillis = elapsed;
        lastInputTokens = inputTokens;
        lastOutputTokens = outputTokens;
        usageAvailable = true;

        if (fullText.isBlank()) {
            return Command.println(Styles.MUTED.render("（本轮没有返回文本内容）"));
        }
        // 只有完整内容才进历史，半截内容不会污染上下文
        conversation.addAssistantMessage(fullText);

        String rendered = MarkdownRenderer.render(fullText, Math.max(30, width - 2));
        String meta = Styles.MUTED.render("✻ " + spinnerVerb + " for " + formatSeconds(elapsed)
                + " · ↑" + inputTokens + " ↓" + outputTokens);
        return Command.println(rendered + "\n" + meta);
    }

    /** 本轮失败：错误以独立样式展示，程序不退出，用户可以直接问下一句（F11）。 */
    private Command failTurn(String message) {
        streaming = false;
        String partial = streamBuf.toString();
        streamBuf.setLength(0);
        // 中断时已经收到的那部分照样落成消息，下一轮还能接着聊
        if (!partial.isBlank()) {
            conversation.addAssistantMessage(partial);
        }
        conversation.addErrorMessage(message);
        return Command.println(Styles.ERROR.render("✗ " + message));
    }

    private void activateProvider(int index) {
        ProviderConfig cfg = providers.get(index);
        activeProvider = cfg;
        llmClient = LlmClient.create(cfg, systemPrompt);
    }

    // ───────────────────────── 渲染 ─────────────────────────

    private String viewProviderSelect() {
        List<String> lines = new ArrayList<>();
        lines.add(separator());
        lines.add(Styles.ACCENT.render("  请选择要使用的 provider"));
        lines.add("");
        for (int i = 0; i < providers.size(); i++) {
            ProviderConfig cfg = providers.get(i);
            boolean selected = i == selectedIndex;
            String marker = selected ? Styles.PROMPT.render(" ❯ ") : "   ";
            String label = cfg.getName() + "  (" + cfg.getModel() + ")";
            lines.add(marker + (selected ? Styles.USER.render(label) : Styles.MUTED.render(label)));
        }
        lines.add("");
        lines.add(Styles.MUTED.render("  ↑↓ 移动，Enter 确认，Ctrl+C 退出"));
        lines.add(separator());
        return String.join("\n", lines);
    }

    private String viewChat() {
        List<String> lines = new ArrayList<>();
        lines.add(separator());

        if (streaming) {
            long elapsed = System.currentTimeMillis() - requestStartMillis;
            // 自请求发出就开始计时，第一个字还没到也能看到秒数在跳（F12 / N2）
            lines.add(Styles.SPINNER.render("✻ " + SpinnerVerbs.frame() + " " + spinnerVerb + "… ("
                    + (elapsed / 1000) + "s)"));
            if (!streamBuf.isEmpty()) {
                lines.addAll(List.of(streamBuf.toString().split("\n", -1)));
            }
            lines.add(separator());
        }

        lines.addAll(renderInputBox());
        lines.add(renderStatusBar());
        return String.join("\n", lines);
    }

    /** 底部带边框的输入框，含 ❯ 提示符与占位文字（F7-d）。 */
    private List<String> renderInputBox() {
        int boxWidth = Math.max(20, width);
        int innerWidth = boxWidth - 4;

        List<String> rows = new ArrayList<>();
        String raw = input.toString();
        if (raw.isEmpty()) {
            rows.add(Styles.PROMPT.render("❯ ") + Styles.PLACEHOLDER.render("Send a message..."));
        } else {
            String[] logicalLines = raw.split("\n", -1);
            for (int i = 0; i < logicalLines.length; i++) {
                List<String> wrapped = wrapPlain(logicalLines[i], innerWidth - 2);
                for (int j = 0; j < wrapped.size(); j++) {
                    String prefix = (i == 0 && j == 0) ? Styles.PROMPT.render("❯ ") : "  ";
                    rows.add(prefix + wrapped.get(j));
                }
            }
        }

        List<String> out = new ArrayList<>();
        out.add(Styles.BORDER.render("╭" + "─".repeat(boxWidth - 2) + "╮"));
        for (String row : rows) {
            String padded = row + " ".repeat(Math.max(0, innerWidth - Ansi.displayWidth(row)));
            out.add(Styles.BORDER.render("│") + " " + padded + " " + Styles.BORDER.render("│"));
        }
        out.add(Styles.BORDER.render("╰" + "─".repeat(boxWidth - 2) + "╯"));
        return out;
    }

    /** 状态栏：左侧 provider 名，右侧模型名 + token 用量 + 本轮耗时（F7-e）。 */
    private String renderStatusBar() {
        String left = activeProvider == null ? "-" : activeProvider.getName();
        String right = activeProvider == null ? "-" : activeProvider.getModel();
        if (usageAvailable) {
            right += " · ↑" + lastInputTokens + " ↓" + lastOutputTokens;
        }
        if (lastElapsedMillis > 0) {
            right += " · " + formatSeconds(lastElapsedMillis);
        }
        int gap = Math.max(1, width - Ansi.displayWidth(left) - Ansi.displayWidth(right));
        return left + " ".repeat(gap) + right;
    }

    private String renderBanner() {
        String[] cat = {
                "   /\\_/\\",
                "  ( o.o )",
                "   > ^ <",
        };
        String[] info = {
                Styles.USER.render(APP_NAME + " v" + VERSION),
                Styles.MUTED.render(workingDir),
                "",
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cat.length; i++) {
            sb.append(cat[i]);
            if (i < info.length && !info[i].isEmpty()) {
                sb.append("   ").append(info[i]);
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** 启动后的一行就绪提示（F7-b）。 */
    private String renderReadyLine() {
        return Styles.ACCENT.render("✻ 已就绪")
                + Styles.MUTED.render(" · 输入 " + EXIT_COMMAND + " 或按 Ctrl+C 退出");
    }

    private String separator() {
        return Styles.BORDER.render("─".repeat(Math.max(10, width)));
    }

    // ───────────────────────── 小工具 ─────────────────────────

    /** 按可见宽度软换行；输入缓冲里没有转义序列，所以按码位算宽度就够。 */
    private static List<String> wrapPlain(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (maxWidth <= 1) {
            out.add(text);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        int w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int cw = cp > 0x2E80 ? 2 : 1;
            if (w + cw > maxWidth) {
                out.add(cur.toString());
                cur.setLength(0);
                w = 0;
            }
            cur.appendCodePoint(cp);
            w += cw;
            i += Character.charCount(cp);
        }
        out.add(cur.toString());
        return out;
    }

    private static String formatSeconds(long millis) {
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    /** 工具调用参数的紧凑预览：直接打印 Map，过长截断。 */
    private static String previewArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        String s = args.toString();
        if (s.length() > 100) {
            s = s.substring(0, 100) + "…";
        }
        return s;
    }

    /** 工具结果的紧凑预览：取首行，过长截断。 */
    private static String previewResult(String content) {
        if (content == null || content.isBlank()) {
            return "(空)";
        }
        String firstLine = content.split("\n", -1)[0];
        if (firstLine.length() > 120) {
            firstLine = firstLine.substring(0, 120) + "…";
        }
        int lines = (int) content.lines().count();
        return lines > 1 ? firstLine + " (+" + (lines - 1) + " 行)" : firstLine;
    }
}
