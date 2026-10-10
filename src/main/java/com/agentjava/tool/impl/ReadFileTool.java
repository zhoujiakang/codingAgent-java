package com.agentjava.tool.impl;

import com.agentjava.tool.FileStateCache;
import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolCategory;
import com.agentjava.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 读取文件内容，按行号 + tab 前缀返回，方便 EditFile 定位。
 *
 * <p>默认从头读 2000 行，可用 offset/limit 分页读取大文件。读取时向 FileStateCache
 * 记录修改时间，作为 WriteFile/EditFile「先读再写」的依据。
 */
public class ReadFileTool implements Tool {

    private FileStateCache fileStateCache;

    public void setFileStateCache(FileStateCache c) {
        this.fileStateCache = c;
    }

    private static final String DESCRIPTION = """
            Read a file and return its contents with line numbers.

            Usage notes:
            - 优先使用本工具，而不是 Bash 里的 cat / head / tail。
            - The file_path parameter should be an absolute path when possible.
            - By default reads up to 2000 lines from the beginning of the file.
            - Use offset and limit to read specific parts of large files. Only read what you need.
            - Results are returned with line numbers (1-based) for easy reference.
            - This tool can only read files, not directories. Use Glob to list directory contents.
            - 多个相互独立的读取请求可以放在同一次响应里并行发起。
            - 编辑文件前必须先 ReadFile 读过该文件，EditFile 才能正确匹配 old_string。
            - Do NOT re-read a file you just edited to verify — EditFile would have errored if the change failed.""";

    @Override
    public String name() {
        return "ReadFile";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "Absolute or relative path to the file to read"),
                                "offset", Map.of("type", "integer", "description", "Line offset to start reading from (0-based)", "default", 0),
                                "limit", Map.of("type", "integer", "description", "Maximum number of lines to read", "default", 2000)
                        ),
                        "required", List.of("file_path")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = stringArg(args, "file_path", "");
        if (filePath.isEmpty()) {
            return ToolResult.error("Error: file_path is required");
        }

        int offset = intArg(args, "offset", 0);
        int limit = intArg(args, "limit", 2000);

        Path path = Path.of(filePath);

        if (!Files.exists(path)) {
            return ToolResult.error("Error: file not found: " + filePath);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.error("Error: not a file: " + filePath);
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return ToolResult.error("Error reading file: " + e.getMessage());
        }

        String[] lines = content.split("\n", -1);

        if (offset >= lines.length) {
            return ToolResult.success("");
        }

        int end = offset + limit;
        if (end > lines.length) {
            end = lines.length;
        }

        // 记录到文件状态缓存，让 EditFile/WriteFile 知道该文件已被读取
        if (fileStateCache != null) {
            try {
                long mtime = Files.getLastModifiedTime(path).toMillis();
                fileStateCache.record(path.toAbsolutePath().toString(), mtime);
            } catch (IOException ignored) {
                // best-effort：mtime 读取失败不应让读取本身失败
            }
        }

        var sb = new StringBuilder();
        for (int i = offset; i < end; i++) {
            if (i > offset) {
                sb.append('\n');
            }
            sb.append(i + 1).append('\t').append(lines[i]);
        }

        return ToolResult.success(sb.toString());
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        var v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return def;
    }
}
