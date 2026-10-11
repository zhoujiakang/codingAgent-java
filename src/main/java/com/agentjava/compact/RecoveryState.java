package com.agentjava.compact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 恢复状态的记录：最近访问的文件快照与激活的 Skill 定义。
 *
 * <p>压缩后用它们重建关键上下文——「压缩不是丢记忆，而是把记忆重新打包」。
 * 模型压缩后仍能看到最近读过的文件片段、当前激活的 Skill，不会因为对话变短而「失忆」。
 *
 * <p>工具回调可能从虚拟线程里触发，所以所有读写都包在 {@code synchronized} 块里保护并发。
 */
public final class RecoveryState {

    /** 单个文件快照的 token 预算。 */
    public static final int MAX_FILE_TOKENS = 5_000;

    /** 最多保留的文件数。 */
    public static final int MAX_FILES = 5;

    /** Skill 定义的总 token 预算。 */
    public static final int MAX_SKILL_TOKENS = 25_000;

    /** 文件快照：路径 + 内容片段（已截断到 token 预算）。 */
    public record FileSnapshot(String path, String content) {
    }

    /** Skill 定义：名称 + 描述/定义文本。 */
    public record SkillDef(String name, String definition) {
    }

    private final Deque<FileSnapshot> files = new ArrayDeque<>();
    private final List<SkillDef> skills = new ArrayList<>();

    public RecoveryState() {
    }

    /** 记录一次文件访问；超过预算截断，超过上限时淘汰最旧的，同路径去重后放到最新。 */
    public synchronized void recordFile(String path, String content) {
        if (path == null || path.isBlank()) {
            return;
        }
        String snippet = (content == null) ? "" : content;
        int maxChars = (int) (MAX_FILE_TOKENS * 3.5);
        if (snippet.length() > maxChars) {
            snippet = snippet.substring(0, maxChars);
        }
        files.removeIf(f -> f.path().equals(path));
        files.addLast(new FileSnapshot(path, snippet));
        while (files.size() > MAX_FILES) {
            files.pollFirst();
        }
    }

    /** 记录一个激活的 Skill 定义（同名称去重后更新）。 */
    public synchronized void recordSkill(String name, String definition) {
        if (name == null || name.isBlank()) {
            return;
        }
        skills.removeIf(s -> s.name().equals(name));
        skills.add(new SkillDef(name, definition == null ? "" : definition));
    }

    public synchronized List<FileSnapshot> files() {
        return new ArrayList<>(files);
    }

    public synchronized List<SkillDef> skills() {
        return new ArrayList<>(skills);
    }

    public synchronized void clear() {
        files.clear();
        skills.clear();
    }

    /**
     * 只清 Skill 记录，保留文件快照。
     * {@code /clear} 开新会话时用：Skill 属于「这次会话在按哪份 SOP 干活」，
     * 换会话就该清；而最近读过的文件仍然大概率用得上。
     */
    public synchronized void clearSkills() {
        skills.clear();
    }
}
