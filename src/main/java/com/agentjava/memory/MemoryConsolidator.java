package com.agentjava.memory;

import com.agentjava.agent.Agent;
import com.agentjava.agent.AgentEvent;
import com.agentjava.conversation.ConversationManager;
import com.agentjava.llm.LlmClient;
import com.agentjava.permission.PermissionChecker;
import com.agentjava.permission.PermissionMode;
import com.agentjava.permission.PermissionResponse;
import com.agentjava.prompt.PromptBuilder;
import com.agentjava.session.SessionManager;
import com.agentjava.tool.ToolRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 记忆治理：在后台 fork 一个子 Agent，合并重复、删除过时、修正矛盾、维护索引。
 *
 * <p>提取系统负责把新信息写进记忆，治理系统负责把旧信息清理干净。没有治理的话，
 * 每次提取都是一次追加：三个月后 {@code ~/.agentjava/memory/} 里会有三条内容几乎一样的
 * 用户画像，模型召回时不知道该信哪一条。
 *
 * <p><b>五道门控从轻到重依次排列</b>：时间门和扫描节流是纯内存判断，代价最低，放在最前面
 * 挡掉绝大多数调用；会话门需要扫文件系统列出最近会话，代价稍高，放在第三层；
 * 锁获取涉及文件读写和 PID 检查，最重，放在最后。绝大多数 Loop 完成时只走一两个 if 就返回，
 * 不会给正常交互带来可感知的延迟。
 */
public class MemoryConsolidator {

    /** 锁持有者心跳：超过这个时间没更新就认为持有进程已经死了，锁自动过期。 */
    private static final long HOLDER_STALE_MS = 3_600_000L;

    /** 扫描节流：10 分钟内只检查一次会话列表。 */
    private static final long SCAN_THROTTLE_MS = 600_000L;

    /** 锁文件名（放在项目级记忆目录下）。 */
    private static final String LOCK_NAME = ".consolidate-lock";

    /** 子 Agent 的迭代上限，防止整理过程中陷入死循环。 */
    private static final int MAX_ITERATIONS = 15;

    private final String workDir;
    private final Path memDir;
    private final Path userMemDir;
    private final String transcriptDir;
    private final int minHours;
    private final int minSessions;

    /** 上次扫描会话列表的时间戳（内存态，进程重启即失效，只用于节流）。 */
    private long lastScanAt = 0;

    public MemoryConsolidator(String workDir, Path memDir, Path userMemDir,
                              String transcriptDir, int minHours, int minSessions) {
        this.workDir = workDir;
        this.memDir = memDir;
        this.userMemDir = userMemDir;
        this.transcriptDir = transcriptDir;
        this.minHours = minHours;
        this.minSessions = minSessions;
    }

    /** 便捷构造：记忆目录取自 {@link MemoryManager}，会话目录取自 {@link SessionManager}。 */
    public static MemoryConsolidator forMemoryManager(MemoryManager memory, String workDir) {
        return new MemoryConsolidator(workDir, memory.projectMemDir(), memory.userMemDir(),
                SessionManager.sessionsDir(workDir).toString(), 24, 5);
    }

    /**
     * 按五级门控判断是否该整理一次，全部通过才真正执行。
     *
     * <p>调用方每轮 Loop 完成都会调它，绝大多数调用会在前两道门就返回。
     */
    public void maybeRun(LlmClient client, ConversationManager conversation, String protocol) {
        if (client == null) {
            return;
        }
        // 门 1：目录不存在说明还没有任何记忆，谈不上整理
        if (memDir == null || !Files.isDirectory(memDir)) {
            return;
        }

        // 门 2：时间门——距上次整理不足 minHours 小时，跳过
        long lastAt = readLastConsolidatedAt();
        double hoursSince = (System.currentTimeMillis() - lastAt) / 3_600_000.0;
        if (hoursSince < minHours) {
            return;
        }

        // 门 3：扫描节流——min(10) 分钟内只检查一次会话列表
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_THROTTLE_MS) {
            return;
        }
        lastScanAt = now;

        // 门 4：会话门——累积不足 minSessions 个新会话，跳过
        List<String> sessionIds = SessionManager.listSessionsSince(workDir, lastAt);
        if (sessionIds.size() < minSessions) {
            return;
        }

        // 门 5：获取锁
        Long priorMtime = tryAcquireLock();
        if (priorMtime == null) {
            return;
        }

        final long priorMtimeFinal = priorMtime;
        Thread.startVirtualThread(() -> {
            try {
                run(client, protocol, sessionIds);
                touchLock();
            } catch (Exception e) {
                rollbackLock(priorMtimeFinal);
            }
        });
    }

    private void run(LlmClient client, String protocol, List<String> sessionIds) {
        if (userMemDir == null || transcriptDir == null) {
            return;
        }
        String prompt = buildConsolidationPrompt(
                memDir.toString(), userMemDir.toString(), transcriptDir, sessionIds);

        ToolRegistry registry = ToolRegistry.createDefault();
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage(prompt);

        Agent subAgent = new Agent(client, registry, protocol,
                PromptBuilder.buildSystemPrompt(PromptBuilder.detectEnvironment(null), null));
        subAgent.setPermissionMode(PermissionMode.BYPASS);
        subAgent.setWorkDir(workDir);
        subAgent.setMaxIterations(MAX_ITERATIONS);

        BlockingQueue<AgentEvent> queue = subAgent.run(conv);
        while (true) {
            AgentEvent event;
            try {
                event = queue.poll(600, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (event == null) {
                return;
            }
            // 后台跑没有用户可以交互，理论上 BYPASS 下不会有权限请求；
            // 真来了就直接放行，否则子 Agent 会永远阻塞在这个 future 上
            if (event instanceof AgentEvent.PermissionRequestEvent pre) {
                pre.response().complete(PermissionResponse.ALLOW);
                continue;
            }
            if (event instanceof AgentEvent.LoopComplete || event instanceof AgentEvent.ErrorEvent) {
                return;
            }
        }
    }

    /**
     * 四阶段整理提示词：定位现有记忆 → 从会话记录捞新信号 → 合并更新 → 修剪索引。
     *
     * <p>Consolidate 阶段的三条规则对应三种典型的记忆退化：
     * 合并重复（同一件事写了三遍）、相对日期转绝对日期（"上周改的"放三个月后毫无意义）、
     * 删除被推翻的旧事实。判断全部交给 LLM，不做规则匹配——这些判断本质上需要语义理解。
     *
     * <p>Bash 工具虽然给了，但用文字限制为只读：整理过程没人盯着，不能让它顺手改了代码。
     */
    static String buildConsolidationPrompt(String memDirStr, String userMemDirStr,
                                           String transcriptDirStr, List<String> sessionIds) {
        String sessionList = String.join("\n", sessionIds);
        return """
                You are a memory consolidation agent. Your job is to maintain a set of long-term
                memory files so they stay accurate, non-redundant and useful.

                ## Phase 1 — Orient

                Read every .md file in these directories and build a picture of what is already known:
                - %s  (project memories)
                - %s  (user memories)

                ## Phase 2 — Gather signals

                Look for new signals in the recent session transcripts under %s:
                %s

                ## Phase 3 — Consolidate

                Update the memory files according to these rules:
                1. Merge duplicate memories into one file instead of creating near-identical copies.
                2. Convert relative dates ("last week", "yesterday") into absolute dates.
                3. Delete facts that have been superseded or contradicted by later information.

                Each memory file must keep YAML frontmatter with name, description and
                metadata.type (one of user, feedback, project, reference).

                ## Phase 4 — Prune and index

                Rewrite %s/MEMORY.md so it contains exactly one pointer line
                (`- [Name](file.md) — description`) for each remaining memory file,
                removing pointers to deleted files. Keep the index under 200 lines.

                Constraints:
                - Use Bash only for read-only commands. Do not modify the user's source code.
                - Do not create new memory files unless a genuinely new topic emerged.
                """.formatted(memDirStr, userMemDirStr, transcriptDirStr, sessionList, memDirStr);
    }

    // ───────────────────────── 文件锁 ─────────────────────────

    private Path lockPath() {
        return memDir.resolve(LOCK_NAME);
    }

    /** 上次整理完成的时间戳，取自锁文件的 mtime；文件不存在时返回 0（从未整理过）。 */
    long readLastConsolidatedAt() {
        Path path = lockPath();
        if (!Files.exists(path)) {
            return 0L;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 尝试获取锁。
     *
     * <p>锁文件同时承载两重语义：内容是持有者的 PID，mtime 记录上次整理完成的时间。
     * 获取时先看文件是否存在，存在且 mtime 在一小时内，再用
     * {@code ProcessHandle.of(pid).isAlive()} 检查持有进程是否还活着，活着就放弃。
     * 这样即使进程崩溃，一小时后锁也会自动过期被下一个实例回收。
     *
     * <p>写入 PID 后还要回读一遍验证——防止两个进程同时写入的竞争窗口。
     *
     * @return 获取成功返回锁文件原有的 mtime（供失败回滚用）；未获取到返回 null
     */
    Long tryAcquireLock() {
        Path path = lockPath();
        Long mtimeMs = null;
        Long holderPid = null;

        if (Files.exists(path)) {
            try {
                mtimeMs = Files.getLastModifiedTime(path).toMillis();
                String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!raw.isEmpty()) {
                    holderPid = Long.parseLong(raw);
                }
            } catch (Exception e) {
                // 锁文件坏了就当作没有持有者，下面的写入会覆盖它
            }
        }

        if (mtimeMs != null && System.currentTimeMillis() - mtimeMs < HOLDER_STALE_MS) {
            if (holderPid != null && isProcessRunning(holderPid)) {
                return null;
            }
        }

        try {
            Files.createDirectories(memDir);
            Files.writeString(path, String.valueOf(ProcessHandle.current().pid()),
                    StandardCharsets.UTF_8);
            String verify = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (Long.parseLong(verify) != ProcessHandle.current().pid()) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return mtimeMs != null ? mtimeMs : 0L;
    }

    /** 整理成功后，把锁文件 mtime 更新为当前时间——它同时是「上次整理完成」的时间戳。 */
    private void touchLock() {
        try {
            Path path = lockPath();
            Files.writeString(path, String.valueOf(ProcessHandle.current().pid()),
                    StandardCharsets.UTF_8);
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(
                    System.currentTimeMillis()));
        } catch (IOException e) {
            // 时间戳更新不了只是让下次整理提前触发，不影响正确性
        }
    }

    /** 整理失败时把 mtime 恢复原值，避免跳过本该执行的整理周期。 */
    void rollbackLock(long priorMtime) {
        try {
            Files.setLastModifiedTime(lockPath(), java.nio.file.attribute.FileTime.fromMillis(
                    priorMtime > 0 ? priorMtime : System.currentTimeMillis() - HOLDER_STALE_MS));
        } catch (IOException e) {
            // 回滚失败无所谓，锁迟早会因为超时被回收
        }
    }

    private static boolean isProcessRunning(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** 包可见的测试入口：直接跑一次整理（跳过门控），供 VerifySessionMemory 验证 wiring。 */
    void runForTest(LlmClient client, String protocol, List<String> sessionIds) {
        run(client, protocol, sessionIds);
    }
}
