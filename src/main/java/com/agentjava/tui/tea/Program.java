package com.agentjava.tui.tea;

import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TUI 运行时（Bubble Tea 风格的 Program）。
 *
 * 负责三件 Model 不该干的事：
 * <ol>
 *   <li>把终端切进 raw mode，读键盘、解析 ESC 序列；</li>
 *   <li>把消息喂给 Model，拿到新状态后代为执行副作用指令；</li>
 *   <li>内联渲染：每帧用 cursor-up 覆写上一帧，不用 alternate screen，
 *       这样启动前终端里已有的内容不会被清掉，写完的东西也自然进入 scrollback。</li>
 * </ol>
 *
 * 主线程跑事件循环，流式网络请求跑在 virtual thread 里，
 * 两边通过 {@link #send(Message)} 通信，互不阻塞（N1）。
 */
public class Program {

    /** 空闲时的重绘间隔；spinner 与响应计时靠它跳动。 */
    private static final long TICK_MILLIS = 100;

    /** 尺寸下限：部分环境下拿不到真实尺寸，兜底用。 */
    private static final int MIN_WIDTH = 40;
    private static final int MIN_HEIGHT = 10;

    private final Terminal terminal;
    private final BlockingQueue<Message> queue = new LinkedBlockingQueue<>();

    private Model model;
    private volatile boolean running = false;
    private volatile boolean cleanedUp = false;
    private int linesRendered = 0;
    private int width = 80;
    private int height = 24;
    private Attributes savedAttributes;

    /**
     * 上一次真正写到屏幕上的视图内容（已按高度裁切、用换行拼接）。
     * 空闲时视图不变，若与本次完全相同就跳过整段擦除+重绘，
     * 避免输入框每 100ms 被反复擦写产生闪烁（F7）。
     */
    private String displayedView = null;

    /**
     * 增量模式下正文当前的可见列数。
     *
     * <p>正文是自己按列宽折行的，所以要记住"写到第几列了"。状态行是插在
     * 正文末尾之后的，撤下它时需要知道退回几列才能回到正文的落笔点。
     */
    private int textCol = 0;

    /** 临时状态行当前占的可见列数；0 表示此刻没有状态行。 */
    private int statusWidth = 0;

    public Program(Model model) throws IOException {
        this.model = model;
        this.terminal = TerminalBuilder.builder().system(true).build();
    }

    /** 当前终端宽度（列数）。 */
    public int getWidth() {
        return width;
    }

    /** 当前终端高度（行数）。 */
    public int getHeight() {
        return height;
    }

    /**
     * 外部线程投递消息。
     *
     * 流式网络请求跑在别的线程上，拿到事件后就用这个方法把消息塞回事件循环，
     * 避免在渲染线程上直接改状态。
     */
    public void send(Message msg) {
        if (msg != null && running) {
            queue.offer(msg);
        }
    }

    /** 启动事件循环，直到收到退出指令或控制流结束。 */
    public void run() {
        running = true;
        try {
            savedAttributes = terminal.enterRawMode();
            terminal.puts(InfoCmp.Capability.cursor_invisible);
            terminal.flush();
            // 兜底：就算是被信号直接打死，也要把终端还回去（N7）
            Runtime.getRuntime().addShutdownHook(new Thread(this::restoreTerminalQuietly));

            startReader();
            measureSize();
            execute(model.init());
            dispatch(new WindowSizeMessage(width, height));

            while (running) {
                Message msg = queue.poll(TICK_MILLIS, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    // 空闲帧：顺便看看终端尺寸是否被改过
                    if (measureSize()) {
                        dispatch(new WindowSizeMessage(width, height));
                    }
                    // 没有新内容到达时才刷新临时状态行（转圈在跳、耗时在走）。
                    // 正文正在往外吐的时候队列不空，这一行自然让位给正文，不抢光标。
                    refreshStatusLine();
                } else if (msg instanceof QuitMessage) {
                    break;
                } else {
                    dispatch(msg);
                }
                render();
            }
        } catch (Exception e) {
            // 事件循环里的意外不该变成一屏堆栈，静默收尾即可
        } finally {
            cleanup();
        }
    }

    // ───────────────────────── 消息派发与指令执行 ─────────────────────────

    private void dispatch(Message msg) {
        UpdateResult<? extends Model> result = model.update(msg);
        if (result == null) {
            return;
        }
        if (result.model() != null) {
            model = result.model();
        }
        execute(result.command());
    }

    private void execute(Command command) {
        if (command == null || command instanceof Command.None) {
            return;
        }
        if (command instanceof Command.Quit) {
            running = false;
        } else if (command instanceof Command.PrintLine printLine) {
            printLine(printLine.text());
        } else if (command instanceof Command.AppendText appendText) {
            appendText(appendText.text());
        } else if (command instanceof Command.Batch batch) {
            for (Command c : batch.commands()) {
                execute(c);
            }
        }
    }

    // ───────────────────────── 渲染 ─────────────────────────

    /**
     * 把一段文本真正写进终端历史（scrollback）。
     *
     * 做法：先擦掉当前这一帧，再把文本写出去，并把已渲染行数清零，
     * 下一帧会重新画在文本下方。这样写完的内容就永远留在终端里了。
     */
    private void printLine(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        clearStatusRow();
        // 追加模式下正文可能停在行中间，写历史前先换到干净的一行，
        // 免得这段文本被粘在正文末尾
        if (textCol > 0) {
            terminal.writer().write("\r\n");
            textCol = 0;
        }
        clearRendered();
        var writer = terminal.writer();
        writer.write(text.replace("\n", "\r\n"));
        writer.write("\r\n");
        linesRendered = 0;
        textCol = 0;
        terminal.flush();
    }

    /**
     * 就地追加一段文本到光标处，不擦除任何已有内容。
     *
     * <p>这是"不重渲染"的关键：流式正文一旦落到终端历史里就改不回去了，
     * 所以模型一个字一个词地往外吐时，直接把字写在光标处就行，
     * 不必擦掉整帧再重画整帧。
     *
     * <p>折行由调用方（Model）负责——它知道终端宽度，也知道正文写到第几列了。
     * 这里只认 {@code \n}：遇到就换行，其余字符原样写出并累加列数。
     */
    private void appendText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // 状态行挡在正文落笔点后面，先撤掉；它会把光标退回到正文末尾
        clearStatusRow();
        // 首次追加时把"帧"（输入框 + 状态栏）撤下来，之后就是纯追加了
        clearRendered();

        var writer = terminal.writer();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp == '\n') {
                writer.write("\r\n");
                textCol = 0;
            } else {
                writer.write(Character.toChars(cp));
                textCol += Ansi.charWidth(cp);
            }
            i += Character.charCount(cp);
        }
        terminal.flush();
    }

    /** 按模型当前状态刷新那一行临时状态；模型说没有就撤掉。 */
    private void refreshStatusLine() {
        String status = model.statusLine();
        if (status == null || status.isEmpty()) {
            clearStatusRow();
            return;
        }
        clearStatusRow();
        // 正文快占满这一行时不画，免得状态被顶到下一行、把折行算乱
        int room = width - textCol - 1;
        if (room < 6) {
            return;
        }
        String shown = Ansi.truncate(status, room);
        terminal.writer().write(shown);
        statusWidth = Ansi.displayWidth(shown);
        terminal.flush();
    }

    /**
     * 撤掉临时状态行：光标退回它的起点并清到行尾。
     *
     * <p>状态行就画在正文落笔点之后，所以退 {@code statusWidth} 列正好回到
     * 正文的末尾——正文一个字符都不会被碰到，也不会多出空行。
     */
    private void clearStatusRow() {
        if (statusWidth <= 0) {
            return;
        }
        var writer = terminal.writer();
        writer.write("\u001b[" + statusWidth + "D");
        writer.write("\u001b[K");
        statusWidth = 0;
    }

    private void render() {
        String view = model.view();

        // 追加模式：这一轮模型不产出帧（正文直接写进终端历史），
        // 把可能残留的旧帧撤掉即可，不画任何东西。
        if (view == null || view.isEmpty()) {
            clearRendered();
            return;
        }

        String[] lines = view.split("\n", -1);

        // 超过可用高度就只留下半部分，避免把终端内容顶出屏幕
        int maxLines = Math.max(3, height - 1);
        if (lines.length > maxLines) {
            lines = Arrays.copyOfRange(lines, lines.length - maxLines, lines.length);
        }

        // 与上次实际写屏内容比较：完全相同说明视图没变（比如空闲时的静态输入框），
        // 直接跳过擦除+重绘，从根本上消除每 100ms 一次的闪烁（F7）。
        String display = String.join("\n", lines);
        if (display.equals(displayedView)) {
            return;
        }

        clearRendered();
        var writer = terminal.writer();
        for (int i = 0; i < lines.length; i++) {
            // 逐行截断到终端宽度，保证不折行——否则行数统计会错位
            writer.write(Ansi.truncate(lines[i], width));
            if (i < lines.length - 1) {
                writer.write("\r\n");
            }
        }
        linesRendered = lines.length;
        displayedView = display;
        // 帧接管了屏幕，正文的列数记账作废——下一次追加重头开始
        textCol = 0;
        terminal.flush();
    }

    /** 擦掉上一帧：从最后一行开始，清一行、上移一行。 */
    private void clearRendered() {
        if (linesRendered <= 0) {
            return;
        }
        var writer = terminal.writer();
        for (int i = 0; i < linesRendered; i++) {
            writer.write("\r\u001b[2K");
            if (i < linesRendered - 1) {
                writer.write("\u001b[1A");
            }
        }
        linesRendered = 0;
        // 屏幕内容已被擦掉，下一次渲染必须重画，故使缓存失效
        displayedView = null;
    }

    /** 重新量一次尺寸，返回是否发生了变化。 */
    private boolean measureSize() {
        Size size = terminal.getSize();
        int w = Math.max(MIN_WIDTH, size.getColumns());
        int h = Math.max(MIN_HEIGHT, size.getRows());
        if (w != width || h != height) {
            width = w;
            height = h;
            return true;
        }
        return false;
    }

    // ───────────────────────── 键盘输入 ─────────────────────────

    /** 读键线程：阻塞读不会拖住渲染循环，所以单独开一条 virtual thread。 */
    private void startReader() {
        Thread.startVirtualThread(() -> {
            NonBlockingReader reader = terminal.reader();
            while (running) {
                try {
                    int c = reader.read(200);
                    if (c == NonBlockingReader.EOF) {
                        return;
                    }
                    if (c == NonBlockingReader.READ_EXPIRED) {
                        continue;
                    }
                    KeyPressMessage msg = decode(c, reader);
                    if (msg != null) {
                        queue.offer(msg);
                    }
                } catch (IOException e) {
                    return;
                } catch (RuntimeException e) {
                    // 终端被关掉时读操作可能抛异常，直接结束读键线程
                    return;
                }
            }
        });
    }

    /** 把读到的码位翻译成归一化按键名。 */
    private KeyPressMessage decode(int c, NonBlockingReader reader) throws IOException {
        switch (c) {
            case 3 -> {
                return KeyPressMessage.of("ctrl+c");
            }
            case 4 -> {
                return KeyPressMessage.of("ctrl+d");
            }
            case 9 -> {
                return KeyPressMessage.of("tab");
            }
            // Ctrl+J 在多数终端里就是换行，用来做多行编辑
            case 10 -> {
                return KeyPressMessage.of("ctrl+j");
            }
            case 13 -> {
                return KeyPressMessage.of("enter");
            }
            case 8, 127 -> {
                return KeyPressMessage.of("backspace");
            }
            case 1 -> {
                return KeyPressMessage.of("home");
            }
            case 5 -> {
                return KeyPressMessage.of("end");
            }
            case 21 -> {
                return KeyPressMessage.of("ctrl+u");
            }
            case 23 -> {
                return KeyPressMessage.of("ctrl+w");
            }
            case 12 -> {
                return KeyPressMessage.of("ctrl+l");
            }
            case 27 -> {
                return decodeEscape(reader);
            }
            default -> {
                if (c < 32) {
                    return null;
                }
                return KeyPressMessage.rune(new String(Character.toChars(c)));
            }
        }
    }

    /** 解析 ESC 开头的序列：方向键、Delete、Alt+Enter 等。 */
    private KeyPressMessage decodeEscape(NonBlockingReader reader) throws IOException {
        int c2 = reader.read(60);
        if (c2 == NonBlockingReader.READ_EXPIRED || c2 == NonBlockingReader.EOF) {
            return KeyPressMessage.of("esc");
        }
        if (c2 == '[' || c2 == 'O') {
            int c3 = reader.read(60);
            return switch (c3) {
                case 'A' -> KeyPressMessage.of("up");
                case 'B' -> KeyPressMessage.of("down");
                case 'C' -> KeyPressMessage.of("right");
                case 'D' -> KeyPressMessage.of("left");
                case 'H' -> KeyPressMessage.of("home");
                case 'F' -> KeyPressMessage.of("end");
                case '3' -> {
                    reader.read(60);
                    yield KeyPressMessage.of("delete");
                }
                default -> null;
            };
        }
        // Alt+Enter：终端一般发 ESC + CR，也有发 ESC + LF 的，两种都认
        if (c2 == 13 || c2 == 10) {
            return KeyPressMessage.of("alt+enter");
        }
        return KeyPressMessage.of("esc");
    }

    // ───────────────────────── 收尾 ─────────────────────────

    private void cleanup() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        running = false;
        try {
            clearStatusRow();
            clearRendered();
            String history = model == null ? "" : model.dumpHistory();
            if (history != null && !history.isBlank()) {
                terminal.writer().write(history.replace("\n", "\r\n"));
                terminal.writer().write("\r\n");
            }
            terminal.puts(InfoCmp.Capability.cursor_visible);
            terminal.flush();
        } catch (Exception ignored) {
            // 收尾阶段出错也不需要再处理
        }
        restoreTerminalQuietly();
    }

    /** 恢复终端属性并关闭；被正常收尾和 shutdown hook 共用，所以要幂等。 */
    private void restoreTerminalQuietly() {
        try {
            if (savedAttributes != null) {
                terminal.setAttributes(savedAttributes);
            }
            terminal.close();
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
