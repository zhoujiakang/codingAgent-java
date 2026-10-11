package com.agentjava.session;

import java.time.Instant;

/**
 * 会话摘要信息。
 *
 * <p>没有独立的 {@code .meta} 文件，元信息直接从 JSONL 文件里算出来：
 * 首条用户正文、行数、文件大小、修改时间。{@code gitBranch} 从 {@code .git/HEAD} 读，
 * 便于回看某个会话当时所在的分支。
 */
public record SessionInfo(String id, String firstMessage, int messageCount, long fileSize,
                          String gitBranch, Instant modTime) {
}
