package com.agentjava.teams;

import com.agentjava.permission.PermissionChecker;
import com.agentjava.permission.PermissionMode;
import com.agentjava.subagent.AgentDefinition;
import com.agentjava.subagent.ToolFilter;
import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolRegistry;
import com.agentjava.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线验证：Agent Teams —— 团队管理、文件邮箱、结构化协议、共享任务板、
 * Coordinator 模式、工具过滤与 spawn 分派。
 *
 * <p>团队目录全部走真实磁盘（{@link TempDir} + 替换 {@code user.home}），
 * 这样能真正验证「teamDir 按用户主目录定位」「跨 TeamManager 实例从磁盘重建」
 * 这两条跨进程假设。LLM 相关能力不在这里测——这里要验证的是协作基础设施，
 * 不是模型行为。
 */
class VerifyTeams {

    @TempDir
    Path tempDir;

    private String originalHome;
    private Path home;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        home = tempDir.resolve("home");
        System.setProperty("user.home", home.toString());
        AgentNameRegistry.getInstance().clear();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
        AgentNameRegistry.getInstance().clear();
    }

    // ───────────────────────── 一、团队名与目录 ─────────────────────────

    @Test
    @DisplayName("团队名清洗：非字母数字一律换成连字符并转小写")
    void sanitizeTeamName() {
        assertEquals("my-team", TeamFile.sanitizeTeamName("My Team"));
        assertEquals("hello-world", TeamFile.sanitizeTeamName("Hello World"));
        assertEquals("--", TeamFile.sanitizeTeamName("中文"));
        assertEquals("api-v2-fix", TeamFile.sanitizeTeamName("API_v2.fix"));
        assertEquals("abc123", TeamFile.sanitizeTeamName("abc123"));
    }

    @Test
    @DisplayName("团队目录固定在 ~/.agentjava/teams 下，按清洗后的名字隔离")
    void teamDirsUnderUserHome() {
        Path base = TeamManager.teamsBaseDir();
        assertEquals(home.resolve(".agentjava").resolve("teams"), base);
        assertEquals(base.resolve("my-team"), TeamManager.teamDir("My Team"));
        assertEquals(base.resolve("my-team").resolve("config.json"),
                TeamManager.teamConfigPath("My Team"));
    }

    // ───────────────────────── 二、文件邮箱 ─────────────────────────

    @Test
    @DisplayName("文件邮箱：send → readUnread → markAllRead 的基础闭环")
    void mailBoxBasicFlow() {
        var box = new FileMailBox(tempDir.resolve("mail"));
        box.send("alice", new FileMailBox.MailMessage("lead", "hello alice"));
        box.send("alice", new FileMailBox.MailMessage("bob", "second"));

        var unread = box.readUnread("alice");
        assertEquals(2, unread.size());
        assertEquals("lead", unread.get(0).from());
        assertEquals("hello alice", unread.get(0).text());
        assertFalse(unread.get(0).read(), "读出来的消息应标记为未读");

        box.markAllRead("alice");
        assertTrue(box.readUnread("alice").isEmpty(), "标记已读后不再返回");
    }

    @Test
    @DisplayName("文件邮箱：收件人各自独立，互不串台")
    void mailBoxIsolation() {
        var box = new FileMailBox(tempDir.resolve("mail"));
        box.send("alice", new FileMailBox.MailMessage("lead", "to alice"));
        box.send("bob", new FileMailBox.MailMessage("lead", "to bob"));

        assertEquals(1, box.readUnread("alice").size());
        assertEquals("to alice", box.readUnread("alice").get(0).text());
        assertEquals(1, box.readUnread("bob").size());
        assertEquals("to bob", box.readUnread("bob").get(0).text());
        assertTrue(box.readUnread("carol").isEmpty());
    }

    @Test
    @DisplayName("文件邮箱：跨实例读到同一份落盘消息（模拟跨进程通信）")
    void mailBoxCrossInstance() throws Exception {
        Path dir = tempDir.resolve("mail");
        var sender = new FileMailBox(dir);
        sender.send("alice", new FileMailBox.MailMessage("lead", "from lead"));

        // 另一个实例（等价于另一个进程）直读磁盘
        var reader = new FileMailBox(dir);
        var unread = reader.readUnread("alice");
        assertEquals(1, unread.size());
        assertEquals("from lead", unread.get(0).text());
    }

    @Test
    @DisplayName("文件邮箱：并发写入不丢消息（文件锁 + 同进程互斥）")
    void mailBoxConcurrentWrites() throws Exception {
        Path dir = tempDir.resolve("mail");
        var box = new FileMailBox(dir);
        int writers = 8;
        int perWriter = 5;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(writers);
        for (int w = 0; w < writers; w++) {
            final int id = w;
            Thread.startVirtualThread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        box.send("alice", new FileMailBox.MailMessage("w" + id, "m" + i));
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "写入线程未在超时内完成");
        assertEquals(writers * perWriter, box.readUnread("alice").size(),
                "并发写入一条都不能丢");
    }

    // ───────────────────────── 三、结构化协议 ─────────────────────────

    @Test
    @DisplayName("协议：请求 ID 由随机字节生成，格式为 req- 前缀 + 十六进制")
    void requestIdFormat() {
        String id = TeamProtocol.newRequestId();
        assertTrue(id.startsWith("req-"), "前缀应为 req-");
        assertEquals("req-".length() + 16, id.length(), "8 字节 → 16 位十六进制");
        assertTrue(id.substring(4).matches("[0-9a-f]{16}"));
        assertFalse(id.equals(TeamProtocol.newRequestId()), "随机 ID 不应重复");
    }

    @Test
    @DisplayName("协议：关闭请求 / 应答 / 计划审批四条构造器的字段正确")
    void protocolFactories() {
        var req = TeamProtocol.shutdownRequest("lead", "wrapping up");
        assertEquals(TeamProtocol.SHUTDOWN_REQUEST, req.type());
        assertTrue(req.text().startsWith(TeammateRunner.SHUTDOWN_PREFIX));
        assertNotNull(req.requestId());

        var resp = TeamProtocol.shutdownResponse("alice", req.requestId(), true, "ok");
        assertEquals(TeamProtocol.SHUTDOWN_RESPONSE, resp.type());
        assertEquals(req.requestId(), resp.requestId(), "应答带回原请求 ID");
        assertTrue(TeamProtocol.approved(resp));

        var planReq = TeamProtocol.planApprovalRequest("alice", "plan body");
        assertEquals(TeamProtocol.PLAN_APPROVAL_REQUEST, planReq.type());
        assertEquals("plan body", planReq.text());

        var planResp = TeamProtocol.planApprovalResponse("lead", planReq.requestId(), false, "rework");
        assertEquals(TeamProtocol.PLAN_APPROVAL_RESPONSE, planResp.type());
        assertFalse(TeamProtocol.approved(planResp), "approve=false 不算通过");
    }

    @Test
    @DisplayName("协议：approve 缺省按不同意处理，不把没表态当成点头")
    void approvedDefaultsFalse() {
        var plain = new FileMailBox.MailMessage("lead", "just text");
        assertNull(plain.approve());
        assertFalse(TeamProtocol.approved(plain));

        var noApproval = TeamProtocol.planApprovalRequest("alice", "plan");
        assertFalse(TeamProtocol.approved(noApproval));
    }

    @Test
    @DisplayName("协议：关闭请求既认 type，也认 [shutdown] 文本前缀")
    void shutdownRequestRecognition() {
        var typed = TeamProtocol.shutdownRequest("lead", "bye");
        assertTrue(TeamProtocol.isShutdownRequest(typed));

        // 旧版本 / 用户手塞的纯文本前缀也应识别
        var legacy = new FileMailBox.MailMessage("lead", "[shutdown] please stop");
        assertTrue(TeamProtocol.isShutdownRequest(legacy));

        var normal = new FileMailBox.MailMessage("lead", "just a normal task");
        assertFalse(TeamProtocol.isShutdownRequest(normal));
    }

    // ───────────────────────── 四、团队管理 ─────────────────────────

    @Test
    @DisplayName("团队：创建 → 落盘 config.json → 新实例从磁盘重建")
    void teamPersistAndRebuild() {
        var mgr1 = new TeamManager();
        var team = mgr1.createTeam("Alpha Team", TeamManager.TeamMode.IN_PROCESS, "lead-1", "desc");
        assertEquals("Alpha Team", team.getName());
        assertEquals(TeamManager.TeamMode.IN_PROCESS, team.getMode());
        assertTrue(mgr1.listTeams().contains("Alpha Team"));

        // config.json 确实落到了清洗后的目录
        Path cfg = TeamManager.teamConfigPath("Alpha Team");
        assertTrue(Files.exists(cfg), "团队配置应落盘");

        // 新实例（模拟另一进程）能按名字从磁盘重建
        var mgr2 = new TeamManager();
        var rebuilt = mgr2.getTeam("Alpha Team");
        assertNotNull(rebuilt, "新实例应能从磁盘重建团队");
        assertEquals("Alpha Team", rebuilt.getName());

        // 未落盘的团队查不到
        assertNull(mgr2.getTeam("Nonexistent Team"));
    }

    @Test
    @DisplayName("团队：删除会清掉内存、磁盘目录与名称注册")
    void teamDelete() {
        var mgr = new TeamManager();
        mgr.createTeam("Beta", TeamManager.TeamMode.IN_PROCESS);
        Path dir = TeamManager.teamDir("Beta");
        assertTrue(Files.exists(dir));

        AgentNameRegistry.getInstance().register("worker", "worker");
        mgr.deleteTeam("Beta");
        assertFalse(mgr.listTeams().contains("Beta"));
        assertFalse(Files.exists(dir), "团队目录应被递归删除");
    }

    @Test
    @DisplayName("团队：成员登记、元信息补齐、名单查询")
    void teamMemberRegistry() {
        var mgr = new TeamManager();
        var team = mgr.createTeam("Gamma", TeamManager.TeamMode.IN_PROCESS);
        var member = team.addMember("alice", null, new ToolRegistry(), "openai-compat", null);
        assertEquals("alice", member.getName());
        assertNotNull(member.conv, "成员应带独立对话");

        team.setMemberMeta("alice", "general-purpose", "deepseek-chat", "/tmp/wt");
        assertTrue(team.hasMember("alice"));
        assertEquals(List.of("alice"), team.memberNames());
        assertNotNull(team.getMember("alice"));
        assertNull(team.getMember("bob"));

        // 元信息落盘：新实例重建后仍在
        var team2 = new TeamManager().getTeam("Gamma");
        assertNotNull(team2);
        assertTrue(team2.hasMember("alice"));
    }

    @Test
    @DisplayName("后端检测：TMUX 优先于 ITERM，都为空回退进程内")
    void backendDetection() {
        assertEquals(TeamManager.TeamMode.TMUX, TeamManager.detectBackendFromEnv("t", null));
        assertEquals(TeamManager.TeamMode.ITERM, TeamManager.detectBackendFromEnv(null, "i"));
        assertEquals(TeamManager.TeamMode.TMUX, TeamManager.detectBackendFromEnv("t", "i"),
                "两者同时存在时 TMUX 优先");
        assertEquals(TeamManager.TeamMode.IN_PROCESS, TeamManager.detectBackendFromEnv(null, null));
        assertEquals(TeamManager.TeamMode.IN_PROCESS, TeamManager.detectBackendFromEnv("", ""));
    }

    @Test
    @DisplayName("团队内发消息：落入收件人邮箱")
    void teamSendMessage() {
        var mgr = new TeamManager();
        var team = mgr.createTeam("Delta", TeamManager.TeamMode.IN_PROCESS);
        team.addMember("alice", null, new ToolRegistry(), "openai-compat", null);
        team.sendMessage(TeammateRunner.LEAD_NAME, "alice", "do the thing");

        var msgs = team.getMailBox().readUnread("alice");
        assertEquals(1, msgs.size());
        assertEquals("do the thing", msgs.get(0).text());
        assertEquals(TeammateRunner.LEAD_NAME, msgs.get(0).from());
    }

    // ───────────────────────── 五、共享任务板 ─────────────────────────

    @Test
    @DisplayName("任务板：create / get / list / update 增量更新与状态筛选")
    void sharedTaskStore() {
        var store = new SharedTaskStore(tempDir.resolve("team").resolve("tasks.json"));

        var t1 = store.create("调研", "调查现有实现", "alice", null, null, "lead");
        var t2 = store.create("实现", "写代码", "bob", null, List.of(t1.id()), "lead");
        assertEquals("1", t1.id());
        assertEquals("2", t2.id());
        assertEquals("pending", t1.status());

        // update 是增量：只改传入的字段
        var updated = store.update(t1.id(), "in_progress", null, "更详细的描述", null, null);
        assertEquals("in_progress", updated.status());
        assertEquals("alice", updated.assignee(), "未传的字段保持不变");
        assertEquals("更详细的描述", updated.description());

        // 追加依赖不重复
        var dep1 = store.update(t2.id(), null, null, null, null, List.of(t1.id()));
        assertEquals(List.of(t1.id()), dep1.blockedBy());
        var dep2 = store.update(t2.id(), null, null, null, null, List.of(t1.id()));
        assertEquals(1, dep2.blockedBy().size(), "重复依赖应去重");

        // 按状态筛选
        assertEquals(1, store.listTasks("in_progress", null).size());
        assertEquals(2, store.listTasks(null, null).size());
        assertEquals(1, store.listTasks(null, "bob").size());

        // 更新不存在的任务返回 null
        assertNull(store.update("999", "completed", null, null, null, null));
    }

    @Test
    @DisplayName("任务板：跨实例看到同一张表（按团队名解析到同一份 tasks.json）")
    void sharedTaskStoreSharedAcrossInstances() {
        var mgr = new TeamManager();
        mgr.createTeam("Epsilon", TeamManager.TeamMode.IN_PROCESS);
        var a = mgr.getTaskStore("Epsilon");
        a.create("任务甲", "desc", "alice", null, null, "alice");

        var mgr2 = new TeamManager();
        var b = mgr2.getTaskStore("Epsilon");
        assertEquals(1, b.listTasks(null, null).size(), "另一实例应看到同一张表");
        assertEquals("任务甲", b.listTasks(null, null).get(0).title());
    }

    // ───────────────────────── 六、Coordinator 模式 ─────────────────────────

    @Test
    @DisplayName("Coordinator：只放行五个调度类工具，其它一律收紧")
    void coordinatorToolWhitelist() {
        assertEquals(Set.of("Agent", "SendMessage", "TaskStop", "SyntheticOutput", "TeamDelete"),
                Coordinator.ALLOWED_TOOLS);

        for (String t : Coordinator.ALLOWED_TOOLS) {
            assertTrue(Coordinator.isCoordinatorTool(t), t + " 应在白名单");
        }
        // 会把大段内容灌进 Lead 上下文的工具一律不给
        for (String t : List.of("ReadFile", "WriteFile", "EditFile", "Bash", "Glob", "Grep",
                "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "TeamCreate")) {
            assertFalse(Coordinator.isCoordinatorTool(t), t + " 不应在协调者白名单");
        }
    }

    @Test
    @DisplayName("Coordinator：模式生效与否只看配置，不看团队是否存在")
    void coordinatorActiveFromConfig() {
        assertTrue(Coordinator.isActive(true));
        assertFalse(Coordinator.isActive(false));
    }

    // ───────────────────────── 七、队友工具过滤 ─────────────────────────

    @Test
    @DisplayName("ToolFilter：teammate 过滤在普通子 Agent 基础上再砍掉 TeamCreate/TeamDelete")
    void filterForTeammate() {
        var parent = ToolRegistry.createDefault();
        var def = new AgentDefinition("worker", "通用", List.of(), List.of(), null, 200,
                PermissionMode.DEFAULT, "prompt", null, AgentDefinition.SOURCE_BUILTIN, null);

        var sub = ToolFilter.filterForTeammate(parent, def);
        Set<String> names = new java.util.HashSet<>();
        for (Tool t : sub.listTools()) names.add(t.name());

        assertFalse(names.contains("Agent"), "teammate 不再往下派子 Agent");
        assertFalse(names.contains("TeamCreate"), "建队是 Lead 的职责");
        assertFalse(names.contains("TeamDelete"), "解散团队是 Lead 的职责");
        // 基础工具仍在
        assertTrue(names.contains("ReadFile"));
        assertTrue(names.contains("Bash"));
    }

    @Test
    @DisplayName("ToolFilter：teammate 白名单与 allow 列表一致时只保留白名单工具")
    void filterForTeammateAllowList() {
        var parent = ToolRegistry.createDefault();
        var def = new AgentDefinition("reader", "只读", List.of("ReadFile", "Grep"), List.of(), null, 200,
                PermissionMode.DEFAULT, "prompt", null, AgentDefinition.SOURCE_BUILTIN, null);

        var sub = ToolFilter.filterForTeammate(parent, def);
        Set<String> names = new java.util.HashSet<>();
        for (Tool t : sub.listTools()) names.add(t.name());
        assertEquals(Set.of("ReadFile", "Grep"), names);
    }

    // ───────────────────────── 八、协作工具 ─────────────────────────

    @Test
    @DisplayName("SendMessage 工具：按名字投递给队友，落入其邮箱")
    void sendMessageToolRouting() {
        var mgr = new TeamManager();
        var team = mgr.createTeam("Zeta", TeamManager.TeamMode.IN_PROCESS);
        team.addMember("alice", null, new ToolRegistry(), "openai-compat", null);

        var tool = new TeamTools.SendMessageTool(mgr, TeammateRunner.LEAD_NAME);
        var res = tool.execute(Map.of("to", "alice", "content", "请调研一下"));
        assertFalse(res.isError(), "投递应成功：" + res.output());

        var msgs = team.getMailBox().readUnread("alice");
        assertEquals(1, msgs.size());
        assertEquals("请调研一下", msgs.get(0).text());
    }

    @Test
    @DisplayName("SendMessage 工具：广播 to=* 会投给除自己外的所有成员")
    void sendMessageBroadcast() {
        var mgr = new TeamManager();
        var team = mgr.createTeam("Eta", TeamManager.TeamMode.IN_PROCESS);
        team.addMember("alice", null, new ToolRegistry(), "openai-compat", null);
        team.addMember("bob", null, new ToolRegistry(), "openai-compat", null);
        team.addMember("carol", null, new ToolRegistry(), "openai-compat", null);

        // 广播以成员身份发出：lead 不在 members 里，抓不到「自己所属的团队」
        var tool = new TeamTools.SendMessageTool(mgr, "alice");
        var res = tool.execute(Map.of("to", "*", "content", "全体注意"));
        assertFalse(res.isError(), "广播应成功：" + res.output());

        assertTrue(team.getMailBox().readUnread("alice").isEmpty(), "广播不含发送者自己");
        assertEquals(1, team.getMailBox().readUnread("bob").size());
        assertEquals(1, team.getMailBox().readUnread("carol").size());
    }

    @Test
    @DisplayName("TeamCreate 工具：建队并落盘，名字重复时改写避免撞目录")
    void teamCreateTool() {
        var mgr = new TeamManager();
        var tool = new TeamTools.TeamCreateTool(mgr);
        var res1 = tool.execute(Map.of("team_name", "My Team"));
        assertFalse(res1.isError(), res1.output());
        assertTrue(mgr.listTeams().contains("My Team"));

        var res2 = tool.execute(Map.of("team_name", "My Team"));
        assertFalse(res2.isError(), res2.output());
        assertTrue(mgr.listTeams().size() >= 2, "重名时应改写后建出另一个团队");
    }

    @Test
    @DisplayName("TeamDelete 工具：删队并清目录")
    void teamDeleteTool() {
        var mgr = new TeamManager();
        mgr.createTeam("Theta", TeamManager.TeamMode.IN_PROCESS);
        Path dir = TeamManager.teamDir("Theta");

        var tool = new TeamTools.TeamDeleteTool(mgr);
        var res = tool.execute(Map.of("team_name", "Theta"));
        assertFalse(res.isError(), res.output());
        assertFalse(mgr.listTeams().contains("Theta"));
        assertFalse(Files.exists(dir));
    }

    @Test
    @DisplayName("TaskStop 工具：按名字停下正在跑的队友")
    void taskStopTool() throws Exception {
        var mgr = new TeamManager();
        var team = mgr.createTeam("Iota", TeamManager.TeamMode.IN_PROCESS);
        var member = team.addMember("alice", null, new ToolRegistry(), "openai-compat", null);
        member.active = true;

        var tool = new TaskStopTool(mgr);
        var res = tool.execute(Map.of("teammate", "alice"));
        assertFalse(res.isError(), res.output());
        assertFalse(member.isActive(), "被停下的成员应标记为非活跃");
    }

    @Test
    @DisplayName("TaskStop 工具：目标不存在时返回错误而非抛异常")
    void taskStopUnknownTarget() {
        var mgr = new TeamManager();
        mgr.createTeam("Kappa", TeamManager.TeamMode.IN_PROCESS);
        var tool = new TaskStopTool(mgr);
        var res = tool.execute(Map.of("teammate", "ghost"));
        assertTrue(res.isError(), "不存在的名字应报告错误");
    }

    // ───────────────────────── 九、名称注册表 ─────────────────────────

    @Test
    @DisplayName("名称注册表：登记 / 解析 / 注销 / 清空")
    void nameRegistry() {
        var reg = AgentNameRegistry.getInstance();
        reg.register("alice", "agent-001");
        assertEquals("agent-001", reg.resolve("alice"));
        assertNull(reg.resolve("bob"));

        reg.unregister("alice");
        assertNull(reg.resolve("alice"));

        reg.register("x", "1");
        reg.register("y", "2");
        reg.clear();
        assertNull(reg.resolve("x"));
        assertNull(reg.resolve("y"));
    }

    // ───────────────────────── 十、Spawn 命令行 ─────────────────────────

    @Test
    @DisplayName("Spawn：teammate CLI 带 --teammate --team-name --agent-name 三个参数")
    void buildTeammateCLI() {
        String cli = SpawnDispatcher.buildTeammateCLI("My Team", "alice", "/tmp/project");
        assertTrue(cli.contains("--teammate"), cli);
        assertTrue(cli.contains("--team-name"), cli);
        assertTrue(cli.contains("--agent-name"), cli);
        assertTrue(cli.contains("alice"), cli);
        assertTrue(cli.contains("cd "), cli);
    }

    @Test
    @DisplayName("Spawn：含特殊字符的路径会被安全转义（单引号包裹）")
    void shellQuoteEscapes() {
        assertEquals("/tmp/simple", SpawnDispatcher.shellQuote("/tmp/simple"));
        assertEquals("'a b'", SpawnDispatcher.shellQuote("a b"));
        assertTrue(SpawnDispatcher.shellQuote("it's").contains("'\\''"),
                "内部单引号应被转义");
    }

    // ───────────────────────── 十一、队友附录 ─────────────────────────

    @Test
    @DisplayName("队友附录：告知团队名、自己的名字、队友名单与通信方式")
    void teammateAddendum() {
        String text = TeammateRunner.buildTeammateAddendum("TeamX", "alice", List.of("bob", "carol"));
        assertTrue(text.contains("TeamX"));
        assertTrue(text.contains("alice"));
        assertTrue(text.contains("bob"));
        assertTrue(text.contains("carol"));
        assertTrue(text.contains("SendMessage"));
    }

    @Test
    @DisplayName("Lead 邮箱排空：把未读消息折叠成 team-notification 并标记已读")
    void drainLeadMailbox() {
        var mgr = new TeamManager();
        var team = mgr.createTeam("Lambda", TeamManager.TeamMode.IN_PROCESS);
        team.sendMessage("alice", TeammateRunner.LEAD_NAME, "干完了");

        var notes = TeammateRunner.drainLeadMailbox(mgr);
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("<team-notification"), notes.get(0));
        assertTrue(notes.get(0).contains("alice"), notes.get(0));
        assertTrue(notes.get(0).contains("干完了"), notes.get(0));

        // 二次排空应为空（已标记已读）
        assertTrue(TeammateRunner.drainLeadMailbox(mgr).isEmpty());
    }

    // ───────────────────────── 十二、teammate 权限检查 ─────────────────────────

    @Test
    @DisplayName("计划模式队友：PLAN 模式 checker 只读，ExitPlanMode 前不能改文件")
    void planModeTeammateChecker() {
        var checker = new PermissionChecker(PermissionMode.PLAN, tempDir);
        assertEquals(PermissionMode.PLAN, checker.getMode());
    }

    // ───────────────────────── 十三、锁超时与错误语义 ─────────────────────────

    @Test
    @DisplayName("文件邮箱：陈旧锁超过阈值会被强制接管，不永久死锁")
    void staleLockReclaim() throws Exception {
        Path dir = tempDir.resolve("mail");
        Files.createDirectories(dir);
        // 手工造一个陈旧锁文件（mtime 设为很久以前）
        Path lock = dir.resolve("alice.json.lock");
        Files.createFile(lock);
        Files.setLastModifiedTime(lock,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));

        var box = new FileMailBox(dir);
        box.send("alice", new FileMailBox.MailMessage("lead", "after stale lock"));
        assertEquals(1, box.readUnread("alice").size(), "陈旧锁应被回收后正常写入");
    }

    @Test
    @DisplayName("团队工具：SendMessage 参数缺失时给出错误而非抛异常")
    void sendMessageMissingArgs() {
        var mgr = new TeamManager();
        mgr.createTeam("Mu", TeamManager.TeamMode.IN_PROCESS);
        var tool = new TeamTools.SendMessageTool(mgr, TeammateRunner.LEAD_NAME);
        var res = tool.execute(Map.of("to", "alice"));
        assertTrue(res.isError(), "缺 content 应报错");
    }

    @Test
    @DisplayName("团队工具：给未知队友发消息返回错误")
    void sendMessageUnknownRecipient() {
        var mgr = new TeamManager();
        mgr.createTeam("Nu", TeamManager.TeamMode.IN_PROCESS);
        var tool = new TeamTools.SendMessageTool(mgr, TeammateRunner.LEAD_NAME);
        var res = tool.execute(Map.of("to", "ghost", "content", "hi"));
        assertTrue(res.isError(), "未知收件人应报错");
    }

    @Test
    @DisplayName("任务板：update 未知 id 不抛异常，返回 null")
    void taskUpdateUnknownId() {
        var store = new SharedTaskStore(tempDir.resolve("t").resolve("tasks.json"));
        assertNull(store.update("nope", "completed", null, null, null, null));
    }

    @Test
    @DisplayName("任务板：initEmpty 落一份空的 {next_id, tasks[]}")
    void taskStoreInitEmpty() throws Exception {
        Path file = tempDir.resolve("team").resolve("tasks.json");
        var store = new SharedTaskStore(file);
        store.initEmpty();
        assertTrue(Files.exists(file));
        String json = Files.readString(file);
        assertTrue(json.contains("next_id"), json);
        assertTrue(json.contains("tasks"), json);
    }

    @Test
    @DisplayName("团队管理：closeAll 清空内存但不删磁盘目录")
    void closeAllKeepsDisk() {
        var mgr = new TeamManager();
        mgr.createTeam("Xi", TeamManager.TeamMode.IN_PROCESS);
        Path dir = TeamManager.teamDir("Xi");
        mgr.closeAll();
        assertTrue(mgr.listTeams().isEmpty());
        assertTrue(Files.exists(dir), "closeAll 只停成员，不删目录");
    }
}
