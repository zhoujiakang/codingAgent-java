package com.agentjava.command;

import com.agentjava.conversation.Message;
import com.agentjava.memory.MemoryFile;
import com.agentjava.permission.PermissionMode;
import com.agentjava.session.SessionInfo;
import com.agentjava.session.SessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线验证：命令解析 / 注册 / 别名 / 补全 / 三类命令的执行行为。
 *
 * <p>全部用手写的假 {@link UIController} 驱动——命令层依赖的是接口与 Supplier，
 * 不依赖任何具体 UI，这让命令本身可以脱离终端做验证。
 */
class VerifyCommandFramework {

    // ───────────────────────── 测试替身 ─────────────────────────

    /** 记录命令对 UI 的每一次调用，用来断言「命令只通过接口碰 UI」。 */
    static final class FakeUi implements UIController {
        final List<String> systemMessages = new ArrayList<>();
        String sentUserMessage;
        Boolean planMode;
        int tokenCount = 45_230;
        int refreshCount = 0;

        @Override
        public void addSystemMessage(String text) {
            systemMessages.add(text);
        }

        @Override
        public void sendUserMessage(String text) {
            sentUserMessage = text;
        }

        @Override
        public void setPlanMode(boolean enabled) {
            planMode = enabled;
        }

        @Override
        public int getTokenCount() {
            return tokenCount;
        }

        @Override
        public void refreshStatus() {
            refreshCount++;
        }
    }

    private static CommandRegistry registry() {
        return CommandRegistry.createDefault();
    }

    /** 拼一个默认上下文：参数可以变，其余给一组够用的假数据。 */
    private static CommandContext ctx(FakeUi ui, String args) {
        return new CommandContext(
                args, ui, "/work/demo", "deepseek-chat", "0.1.0", "20261011-101010-abcd",
                () -> PermissionMode.DEFAULT,
                () -> 6,
                () -> 200_000,
                List::<MemoryFile>of,
                List::<MemoryFile>of,
                () -> {
                },
                List::<SessionInfo>of);
    }

    private static CommandContext ctx(FakeUi ui, String args, List<MemoryFile> project,
                                      List<MemoryFile> user, List<SessionInfo> sessions,
                                      Runnable clear) {
        return new CommandContext(
                args, ui, "/work/demo", "deepseek-chat", "0.1.0", "20261011-101010-abcd",
                () -> PermissionMode.DEFAULT,
                () -> 6,
                () -> 200_000,
                () -> user,
                () -> project,
                clear,
                () -> sessions);
    }

    private static String run(CommandRegistry reg, String name, String args, FakeUi ui) {
        Command cmd = reg.find(name).orElseThrow();
        return cmd.handler().handle(args == null ? ctx(ui, "") : ctx(ui, args));
    }

    private static String runWith(CommandRegistry reg, String name, CommandContext context) {
        Command cmd = reg.find(name).orElseThrow();
        return cmd.handler().handle(context);
    }

    private static MemoryFile mem(String filename, String name, String type, String desc) {
        return new MemoryFile("/tmp/memory/" + filename, filename, name, desc, type);
    }

    // ───────────────────────── 解析器 ─────────────────────────

    @Test
    @DisplayName("普通输入不是命令，斜杠开头才是")
    void parseDistinguishesCommands() {
        CommandParser.ParsedInput plain = CommandParser.parse("帮我改个 bug");
        assertFalse(plain.isCommand());
        assertFalse(CommandParser.isCommand("/x 前面有空格但不是开头".substring(2)));

        assertTrue(CommandParser.isCommand("/help"));
        assertTrue(CommandParser.isCommand("   /help"));
        assertFalse(CommandParser.isCommand(""));
        assertFalse(CommandParser.parse(null).isCommand());
    }

    @Test
    @DisplayName("只输入一个斜杠：命令名为空但仍是命令（要的是列命令，不是报错）")
    void parseBareSlashYieldsEmptyName() {
        var parsed = CommandParser.parse("/");
        assertTrue(parsed.isCommand());
        assertEquals("", parsed.name());
        assertEquals("", parsed.args());
    }

    @Test
    @DisplayName("按第一个空格切开，命令名转小写，参数内部保留原样")
    void parseSplitsOnFirstSpaceOnly() {
        var noArgs = CommandParser.parse("/Help");
        assertEquals("help", noArgs.name());
        assertEquals("", noArgs.args());

        var withArgs = CommandParser.parse("/compact 保留数据库 相关内容");
        assertEquals("compact", withArgs.name());
        assertEquals("保留数据库 相关内容", withArgs.args());

        var trailing = CommandParser.parse("   /review   特别注意并发安全  ");
        assertEquals("review", trailing.name());
        assertEquals("特别注意并发安全", trailing.args());
    }

    // ───────────────────────── 定义校验 ─────────────────────────

    @Test
    @DisplayName("非法命令名与「别名等于主名」在构造期就炸")
    void commandValidatesItsOwnIdentity() {
        assertThrows(IllegalArgumentException.class, () ->
                new Command("9help", "数字开头不行", new String[]{}, CommandType.LOCAL, null));
        assertThrows(IllegalArgumentException.class, () ->
                new Command("help", "别名撞主名", new String[]{"help"}, CommandType.LOCAL, null));
        // 大写会被规范化，usage 缺省就是 /主名
        Command cmd = new Command("Help", "帮助", new String[]{"H"}, CommandType.LOCAL, null);
        assertEquals("help", cmd.name());
        assertEquals("h", cmd.aliases().get(0));
        assertEquals("/help", cmd.usage());
        assertTrue(cmd.matches("help"));
        assertTrue(cmd.matches("h"));
    }

    // ───────────────────────── 注册与别名 ─────────────────────────

    @Test
    @DisplayName("主名与别名都能查到同一个命令")
    void findResolvesAliases() {
        CommandRegistry reg = registry();
        assertEquals("help", reg.find("h").orElseThrow().name());
        assertEquals("help", reg.find("?").orElseThrow().name());
        assertEquals("compact", reg.find("c").orElseThrow().name());
        assertEquals("resume", reg.find("r").orElseThrow().name());
        assertTrue(reg.find("没有这条命令").isEmpty());
        assertTrue(reg.find("").isEmpty());
        assertTrue(reg.find(null).isEmpty());
    }

    @Test
    @DisplayName("别名冲突必须在注册期失败，不能等到用户敲命令才发现")
    void aliasConflictFailsFastAtRegistration() {
        CommandRegistry reg = registry();

        // 别名撞已有别名：/s 已经是 status 的
        assertThrows(CommandConflictException.class, () ->
                reg.register(new Command("sessions", "多此一举", new String[]{"s"},
                        CommandType.LOCAL, null)));
        // 别名撞已有主名
        assertThrows(CommandConflictException.class, () ->
                reg.register(new Command("compactx", "多此一举", new String[]{"status"},
                        CommandType.LOCAL, null)));
        // 主名撞已有别名
        assertThrows(CommandConflictException.class, () ->
                reg.register(new Command("p", "多此一举", new String[]{},
                        CommandType.LOCAL, null)));
        // 主名重复
        assertThrows(CommandConflictException.class, () ->
                reg.register(new Command("help", "重复注册", new String[]{},
                        CommandType.LOCAL, null)));

        // 冲突没有污染注册表：原命令还在，且还是原来那条
        assertEquals("status", reg.find("s").orElseThrow().name());
        assertEquals("plan", reg.find("p").orElseThrow().name());
    }

    @Test
    @DisplayName("命令类型是三种，LOCAL_UI 才允许没有 handler")
    void typeSystemIsEnforced() {
        CommandRegistry reg = registry();
        assertEquals(CommandType.LOCAL, reg.find("help").orElseThrow().type());
        assertEquals(CommandType.LOCAL_UI, reg.find("compact").orElseThrow().type());
        assertEquals(CommandType.PROMPT, reg.find("review").orElseThrow().type());

        // compact 是 UI 层兜底的命令，注册中心查不到执行函数
        assertTrue(reg.handlerOf("compact").isEmpty());
        assertTrue(reg.handlerOf("c").isEmpty());
        // help / help 的别名都指向同一个 handler
        assertNotNull(reg.handlerOf("help").orElseThrow());
        assertNotNull(reg.handlerOf("h").orElseThrow());
        assertEquals("local-ui", CommandType.LOCAL_UI.label());
    }

    // ───────────────────────── 隐藏命令与补全 ─────────────────────────

    @Test
    @DisplayName("隐藏命令能执行，但不出现在帮助列表与补全里")
    void hiddenCommandsStayHidden() {
        CommandRegistry reg = registry();
        List<String> visible = reg.listVisible().stream().map(Command::name).toList();
        assertTrue(visible.contains("help"), visible.toString());
        assertFalse(visible.contains("exit"));

        assertTrue(reg.find("exit").isPresent());
        assertTrue(reg.find("quit").isPresent());
        assertFalse(reg.search("e").contains("/exit"));
        assertFalse(reg.search("q").contains("/quit"));
    }

    @Test
    @DisplayName("Tab 补全：名字与别名都能命中，但候选一律返回主名且已排序")
    void completionPrefersCanonicalNames() {
        CommandRegistry reg = registry();
        assertEquals(List.of("/help"), reg.search("he"));
        // 别名 ? 只用来判定匹配，候选补全成主名——补全出 /help 比 /? 更能说明它是干什么的
        assertEquals(List.of("/help"), reg.search("?"));
        assertFalse(reg.search("/c").isEmpty());
        // 多个候选按字母序：clear 在 compact 之前
        assertEquals(List.of("/clear", "/compact"), reg.search("/c"));
        assertEquals(List.of("/compact"), reg.search("com"));
        assertEquals(List.of("/do"), reg.search("d"));
        // 多个候选按字母序：permission 在 plan 之前
        assertEquals(List.of("/permission", "/plan"), reg.search("p"));
        assertTrue(reg.search("").size() >= 10);
        assertTrue(reg.search("zzz").isEmpty());
    }

    // ───────────────────────── /help ─────────────────────────

    @Test
    @DisplayName("/help 列清单，隐藏命令不进清单，/help <命令名> 给详情")
    void helpListsAndDescribes() {
        CommandRegistry reg = registry();
        FakeUi ui = new FakeUi();

        String listing = run(reg, "help", "", ui);
        assertTrue(listing.contains("/session"), listing);
        assertTrue(listing.contains("/review"), listing);
        assertFalse(listing.contains("/exit"), listing);
        assertTrue(listing.contains("输入 /help <命令名> 查看详细用法。"), listing);

        String detail = run(reg, "help", "session", ui);
        assertTrue(detail.contains("/session — 会话管理"), detail);
        assertTrue(detail.contains("用法：/session [list|clean|delete <id>]"), detail);
        assertTrue(detail.contains("类型：local"), detail);

        // 带斜杠也认
        assertTrue(run(reg, "help", "/status", ui).contains("/status, /s — 显示当前状态"));

        String unknown = run(reg, "help", "no-such-cmd", ui);
        assertTrue(unknown.contains("没有这个命令"), unknown);
        assertTrue(unknown.contains("/help"), unknown);
    }

    // ───────────────────────── /status 与 /permission ─────────────────────────

    @Test
    @DisplayName("/status 汇总模式、token、工具、记忆、会话、版本")
    void statusShowsTheWholePicture() {
        CommandRegistry reg = registry();
        FakeUi ui = new FakeUi();
        List<MemoryFile> project = List.of(
                mem("a.md", "构建方式", "project", "gradle"),
                mem("b.md", "目录约定", "reference", "src/main"));
        List<MemoryFile> user = List.of(mem("c.md", "称呼", "user", "叫我老王"));
        String out = runWith(reg, "status", ctx(ui, "", project, user, List.of(), () -> {
        }));

        assertTrue(out.contains("模式：default"), out);
        // token 数取自 UIController，界面上显示的与命令里报的是同一个数字
        assertTrue(out.contains("Token：45,230 / 200,000（23%）"), out);
        assertTrue(out.contains("工具：6 个已启用"), out);
        assertTrue(out.contains("记忆：user 1 条，project 2 条"), out);
        assertTrue(out.contains("会话：20261011-101010-abcd"), out);
        assertTrue(out.contains("模型：deepseek-chat"), out);
        assertTrue(out.contains("工作目录：/work/demo"), out);
        assertTrue(out.contains("版本：v0.1.0"), out);
    }

    @Test
    @DisplayName("/permission 的权限矩阵来自 PermissionMode 本身，不在命令层复制一份")
    void permissionShowsMatrixFromMode() {
        CommandRegistry reg = registry();
        FakeUi ui = new FakeUi();
        String out = run(reg, "permission", "", ui);
        assertTrue(out.contains("当前权限模式：default"), out);
        assertTrue(out.contains("只读工具"), out);
        assertTrue(out.contains("放行"), out);
        assertTrue(out.contains("需确认"), out);
        assertTrue(out.contains("/mode default|accept|plan|yolo"), out);
    }

    // ───────────────────────── /memory ─────────────────────────

    @Test
    @DisplayName("/memory 概要 / 列表 / 需要二次确认的清空")
    void memorySummaryListAndGuardedClear() {
        CommandRegistry reg = registry();
        FakeUi ui = new FakeUi();
        List<MemoryFile> project = List.of(mem("a.md", "构建方式", "project", "gradle"));
        List<MemoryFile> user = List.of(mem("c.md", "称呼", "user", "叫我老王"));
        int[] cleared = {0};
        Runnable clear = () -> cleared[0] = 3;

        String summary = runWith(reg, "memory", ctx(ui, "", project, user, List.of(), clear));
        assertTrue(summary.contains("自动记忆：user 1 条，project 1 条（共 2 条）"), summary);

        String list = runWith(reg, "memory", ctx(ui, "list", project, user, List.of(), clear));
        assertTrue(list.contains("记忆清单（共 2 条）"), list);
        assertTrue(list.contains("[project] 构建方式 — gradle"), list);
        assertTrue(list.contains("[user] 称呼 — 叫我老王"), list);

        // 没有 --yes 只是给警告，不能真删
        String guard = runWith(reg, "memory", ctx(ui, "clear", project, user, List.of(), clear));
        assertTrue(guard.contains("确认执行：/memory clear --yes"), guard);
        assertEquals(0, cleared[0]);

        String done = runWith(reg, "memory", ctx(ui, "clear --yes", project, user, List.of(), clear));
        assertEquals(3, cleared[0]);
        assertTrue(done.contains("已清空 2 条自动记忆"), done);

        String badSub = runWith(reg, "memory", ctx(ui, "wat", project, user, List.of(), clear));
        assertTrue(badSub.startsWith("未知子命令：wat"), badSub);
    }

    @Test
    @DisplayName("没有记忆时给的是说明而不是空列表")
    void memoryEmptyStateExplains() {
        CommandRegistry reg = registry();
        FakeUi ui = new FakeUi();
        String out = run(reg, "memory", "", ui);
        assertTrue(out.contains("还没有任何自动记忆"), out);
    }

    // ───────────────────────── /session ─────────────────────────

    @Test
    @DisplayName("/session 概要 / 列表 / 清理过期 / 拒绝删除当前会话")
    void sessionSubcommands(@TempDir Path tempDir) {
        CommandRegistry reg = registry();
        FakeUi ui = new FakeUi();
        String workDir = tempDir.toString();

        String currentId = SessionManager.newId();
        String otherId = SessionManager.newId();
        SessionManager.saveConversationMessage(workDir, currentId,
                new Message(Message.Role.USER, "第一条", Message.Status.COMPLETE));
        SessionManager.saveConversationMessage(workDir, otherId,
                new Message(Message.Role.USER, "另一个会话", Message.Status.COMPLETE));
        List<SessionInfo> sessions = SessionManager.listSessions(workDir);
        assertEquals(2, sessions.size());

        // 当前会话的信息
        var infoCtx = new CommandContext(
                "", ui, workDir, "m", "0.1.0", currentId,
                () -> PermissionMode.DEFAULT, () -> 6, () -> 200_000,
                List::<MemoryFile>of, List::<MemoryFile>of, () -> {
                }, () -> sessions);
        String info = runWith(reg, "session", infoCtx);
        assertTrue(info.contains("当前会话：" + currentId), info);
        assertTrue(info.contains("消息数：1"), info);

        String list = runWith(reg, "session", new CommandContext(
                "list", ui, workDir, "m", "0.1.0", currentId,
                () -> PermissionMode.DEFAULT, () -> 6, () -> 200_000,
                List::<MemoryFile>of, List::<MemoryFile>of, () -> {
                }, () -> sessions));
        assertTrue(list.contains("Sessions (2)"), list);
        assertTrue(list.contains("* " + currentId), list);
        assertTrue(list.contains(currentId), list);

        // 删别人可以，删当前会话要被拦住——会话指针指向它，删了就没人知道写去哪
        String refuse = runWith(reg, "session", new CommandContext(
                "delete " + currentId, ui, workDir, "m", "0.1.0", currentId,
                () -> PermissionMode.DEFAULT, () -> 6, () -> 200_000,
                List::<MemoryFile>of, List::<MemoryFile>of, () -> {
                }, () -> sessions));
        assertTrue(refuse.contains("不能删除正在使用的会话"), refuse);

        String removed = runWith(reg, "session", new CommandContext(
                "delete " + otherId, ui, workDir, "m", "0.1.0", currentId,
                () -> PermissionMode.DEFAULT, () -> 6, () -> 200_000,
                List::<MemoryFile>of, List::<MemoryFile>of, () -> {
                }, () -> sessions));
        assertTrue(removed.contains("已删除会话 " + otherId), removed);
        assertFalse(SessionManager.listSessions(workDir).stream()
                .anyMatch(s -> s.id().equals(otherId)));

        String cleaned = runWith(reg, "session", new CommandContext(
                "clean", ui, workDir, "m", "0.1.0", currentId,
                () -> PermissionMode.DEFAULT, () -> 6, () -> 200_000,
                List::<MemoryFile>of, List::<MemoryFile>of, () -> {
                }, () -> sessions));
        assertTrue(cleaned.contains("已清理过期会话"), cleaned);

        String badSub = runWith(reg, "session", new CommandContext(
                "resume " + otherId, ui, workDir, "m", "0.1.0", currentId,
                () -> PermissionMode.DEFAULT, () -> 6, () -> 200_000,
                List::<MemoryFile>of, List::<MemoryFile>of, () -> {
                }, () -> sessions));
        // 切会话属于 UI 状态操作，这里明确告诉用户去敲 /resume
        assertTrue(badSub.contains("/resume"), badSub);
    }

    @Test
    @DisplayName("/session 在没有历史存档时不假装成功")
    void sessionEmptyState(@TempDir Path tempDir) {
        CommandRegistry reg = registry();
        FakeUi ui = new FakeUi();
        String out = runWith(reg, "session", new CommandContext(
                "", ui, tempDir.toString(), "m", "0.1.0", "20261011-101010-abcd",
                () -> PermissionMode.DEFAULT, () -> 6, () -> 200_000,
                List::<MemoryFile>of, List::<MemoryFile>of, () -> {
                }, List::<SessionInfo>of));
        assertTrue(out.contains("还没有历史会话"), out);
    }

    // ───────────────────────── PROMPT 类型 ─────────────────────────

    @Test
    @DisplayName("/review 返回的是一段要发给模型的 prompt，并把它会耗 token 这件事说清楚")
    void reviewBuildsPrompt() {
        CommandRegistry reg = registry();
        FakeUi ui = new FakeUi();
        String prompt = run(reg, "review", "", ui);
        assertTrue(prompt.contains("请审查当前 git diff 中的代码变更"), prompt);
        assertTrue(prompt.contains("逻辑错误"), prompt);
        // 会通过 UIController 打招呼，而不是自己往界面上写
        assertEquals(1, ui.systemMessages.size());
        assertTrue(ui.systemMessages.get(0).contains("消耗 token"), ui.systemMessages.get(0));

        String focused = run(reg, "review", "特别注意并发安全", ui);
        assertTrue(focused.contains("额外关注：特别注意并发安全"), focused);
    }

    // ───────────────────────── LOCAL_UI：Plan Mode ─────────────────────────

    @Test
    @DisplayName("/plan 与 /do 只通过 UIController 改 UI 状态，不认识任何 TUI 类")
    void planAndDoDrivePlanModeThroughInterface() {
        CommandRegistry reg = registry();

        FakeUi ui = new FakeUi();
        String entered = run(reg, "plan", "", ui);
        assertTrue(Boolean.TRUE.equals(ui.planMode));
        assertTrue(entered.contains("已进入 Plan Mode"), entered);
        assertEquals(Optional.empty(), Optional.ofNullable(ui.sentUserMessage));

        String withTask = run(reg, "plan", "设计用户认证模块", ui);
        assertTrue(Boolean.TRUE.equals(ui.planMode));
        assertEquals("设计用户认证模块", ui.sentUserMessage);
        assertTrue(withTask.contains("设计用户认证模块"), withTask);

        String exited = run(reg, "do", "", ui);
        assertTrue(Boolean.FALSE.equals(ui.planMode));
        assertTrue(exited.contains("已退出 Plan Mode"), exited);
    }

    // ───────────────────────── 注册中心内部结构 ─────────────────────────

    @Test
    @DisplayName("argPrompt 只在真正需要参数的命令上有值")
    void argPromptOnlyOnCommandsThatNeedArgs(@TempDir Path tempDir) {
        CommandRegistry reg = registry();
        assertTrue(reg.find("resume").orElseThrow().argPrompt().contains("/resume <会话 ID>"));
        assertTrue(reg.find("mode").orElseThrow().argPrompt().contains("/mode"));
        // help 没有参数也能用，不该提示参数
        assertEquals("", reg.find("help").orElseThrow().argPrompt());
        assertEquals("", reg.find("status").orElseThrow().argPrompt());
    }

    @Test
    @DisplayName("并发注册与查找不会互相打乱")
    void registryIsSafeUnderConcurrentUse() throws Exception {
        CommandRegistry reg = new CommandRegistry();
        for (int i = 0; i < 20; i++) {
            final int index = i;
            reg.register(new Command("cmd" + index, "第 " + index + " 条", new String[]{"c" + index},
                    CommandType.LOCAL, ctx -> "ok:" + index));
        }
        List<Thread> threads = new ArrayList<>();
        List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < 8; t++) {
            final int base = t;
            Thread worker = new Thread(() -> {
                for (int i = 0; i < 20; i++) {
                    int index = (base + i) % 20;
                    reg.handlerOf("c" + index).ifPresent(h -> results.add(String.valueOf(index)));
                }
            });
            threads.add(worker);
            worker.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(160, results.size());
        assertEquals("19", reg.find("c19").orElseThrow().name().substring(3));
        assertInstanceOf(String.class, reg.handlerOf("cmd7").orElseThrow()
                .handle(ctx(new FakeUi(), "")));
    }
}
