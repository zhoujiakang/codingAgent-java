package com.agentjava.worktree;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 进入 Worktree 时记录的现场快照（不可变 Record）。
 *
 * <p>Session 与 Worktree 是两个概念：Worktree 是资源（一个目录 + 一个分支），
 * Session 是一次<b>使用行为</b>——谁在用、从哪来、原来的状态是什么。两者分离后，
 * 进入/退出只是 Session 的写与清空，Worktree 资源本身不受会话生命周期影响。
 *
 * <p>用 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 保证向前兼容：
 * 新版本加了字段，旧版本读取时不会因字段缺失而报错。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorktreeSession(
        @JsonProperty("original_cwd") String originalCwd,
        @JsonProperty("worktree_path") String worktreePath,
        @JsonProperty("worktree_name") String worktreeName,
        @JsonProperty("worktree_branch") String worktreeBranch,
        @JsonProperty("original_branch") String originalBranch,
        @JsonProperty("original_head_commit") String originalHeadCommit,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("creation_duration_ms") long creationDurationMs) {

    /** 当前是否处于某个 Worktree 会话内。 */
    public boolean isActive() {
        return worktreePath != null && !worktreePath.isBlank();
    }
}
