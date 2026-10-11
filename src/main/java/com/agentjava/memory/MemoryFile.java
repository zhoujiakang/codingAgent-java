package com.agentjava.memory;

/**
 * 一条自动记忆的展示视图。
 *
 * <p>服务于 {@code /memory list} 这类列表场景——知道这条记忆叫什么、是什么类型就够了。
 * 召回选择器需要更多字段（scope、mtimeMs、filePath），由
 * {@link MemoryScanner.MemoryHeader} 承担。
 *
 * <p>分成两个 record 而不是共用一个大的，好处是展示路径不用为召回才需要的字段
 * 付解析成本：读文件时扫到 frontmatter 就能停。
 */
public record MemoryFile(String path, String filename, String name,
                         String description, String type) {
}
