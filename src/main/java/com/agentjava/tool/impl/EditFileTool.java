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
 * 在文件中精确替换一处字符串。old_string 必须在文件中恰好出现一次。
 *
 * <p>手写 countOccurrences 用 indexOf 循环计数：0 次报「未找到」，多次报「不唯一」。
 * 前置校验保证了只会替换那唯一一处。编辑后同样更新文件状态缓存。
 */
public class EditFileTool implements Tool {

    private FileStateCache fileStateCache;

    public void setFileStateCache(FileStateCache c) {
        this.fileStateCache = c;
    }

    private static final String DESCRIPTION = """
            Replace an exact string in a file. The old_string must appear exactly once in the file.

            Usage notes:
            - You MUST read the file with ReadFile before editing. This tool will fail otherwise.
            - When editing text from ReadFile output, preserve the exact indentation (tabs/spaces) as shown.
            - ALWAYS prefer editing existing files over creating new ones.
            - The edit will FAIL if old_string is not unique in the file. Provide more surrounding context to make it unique.
            - The new_string must be different from old_string.""";

    @Override
    public String name() {
        return "EditFile";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "Path to the file to edit"),
                                "old_string", Map.of("type", "string", "description", "The exact string to find and replace (must be unique in file)"),
                                "new_string", Map.of("type", "string", "description", "The replacement string")
                        ),
                        "required", List.of("file_path", "old_string", "new_string")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String filePath = stringArg(args, "file_path", "");
        String oldStr = stringArg(args, "old_string", "");
        String newStr = stringArg(args, "new_string", "");

        if (filePath.isEmpty()) {
            return ToolResult.error("Error: file_path is required");
        }

        Path path = Path.of(filePath);

        // read-before-edit 强制约束
        if (fileStateCache != null) {
            String absPath = path.toAbsolutePath().toString();
            String err = fileStateCache.validate(absPath);
            if (err != null) {
                return ToolResult.error(err);
            }
        }

        if (!Files.exists(path)) {
            return ToolResult.error("Error: file not found: " + filePath);
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return ToolResult.error("Error reading file: " + e.getMessage());
        }

        int count = countOccurrences(content, oldStr);
        if (count == 0) {
            return ToolResult.error("Error: old_string not found in file");
        }
        if (count > 1) {
            return ToolResult.error("Error: old_string found " + count + " times, must be unique");
        }

        String newContent = content.replace(oldStr, newStr);

        try {
            Files.writeString(path, newContent);
        } catch (IOException e) {
            return ToolResult.error("Error writing file: " + e.getMessage());
        }

        if (fileStateCache != null) {
            fileStateCache.update(path.toAbsolutePath().toString());
        }

        return ToolResult.success("Successfully updated " + filePath);
    }

    private static int countOccurrences(String text, String sub) {
        if (sub.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }
}
