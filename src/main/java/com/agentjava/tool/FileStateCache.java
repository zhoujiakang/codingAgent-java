package com.agentjava.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-before-edit 强制缓存。
 *
 * <p>记录哪些文件被 ReadFile 读过（及其修改时间），让 EditFile / WriteFile
 * 能拒绝修改"模型从没见过"的文件，防止盲写覆盖。
 */
public class FileStateCache {

    // path → mtimeMs
    private final ConcurrentHashMap<String, Long> cache = new ConcurrentHashMap<>();

    /** 记录一个刚被读取的文件，存下 mtime 供后续新鲜度判断。 */
    public void record(String absPath, long mtimeMs) {
        cache.put(absPath, mtimeMs);
    }

    /** 编辑/写入成功后更新缓存，重新读取磁盘上的 mtime。 */
    public void update(String absPath) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(Path.of(absPath)).toMillis();
        } catch (IOException e) {
            mtime = System.currentTimeMillis();
        }
        cache.put(absPath, mtime);
    }

    /**
     * 校验一个文件是否可以安全编辑/写入。
     * 返回 null 表示可以；返回错误字符串表示应当拦截本次操作。
     */
    public String validate(String absPath) {
        Long cachedMtime = cache.get(absPath);
        if (cachedMtime == null) {
            return "Error: file has not been read yet. Read it first before editing.";
        }
        long currentMtime;
        try {
            currentMtime = Files.getLastModifiedTime(Path.of(absPath)).toMillis();
        } catch (IOException e) {
            // 文件可能已被删除；交给调用方自己的存在性判断处理
            return null;
        }
        if (currentMtime > cachedMtime) {
            return "Error: file has been modified since last read. Read it again before editing.";
        }
        return null;
    }
}
