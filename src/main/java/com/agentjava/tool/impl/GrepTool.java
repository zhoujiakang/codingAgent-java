package com.agentjava.tool.impl;

import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolCategory;
import com.agentjava.tool.ToolResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 用正则搜索文件内容，返回 file:line:content 形式的结果。
 *
 * <p>先检测二进制文件（头 512 字节含空字节则跳过），再逐文件逐行匹配。
 * include 参数做文件名过滤。结果累计超过 10000 字符截断，提示模型收窄 pattern。
 */
public class GrepTool implements Tool {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".venv", "node_modules", "__pycache__", ".tox", ".mypy_cache"
    );

    /**
     * 匹配结果输出上限。命中太多时截断并附一行提示，让模型收窄 pattern 重搜，
     * 而不是把满屏匹配全塞进上下文。
     */
    private static final int MAX_MATCH_CHARS = 10_000;

    private static final String DESCRIPTION = """
            Search file contents using a regex pattern, returning file:line:content matches.

            Usage notes:
            - Supports full regex syntax (e.g., "log.*Error", "func\\s+\\w+").
            - Filter files with the include parameter (e.g., "*.py", "*.go").
            - Search from "." or a specific path, never from "/".
            - Use this instead of grep or rg commands via Bash.
            - Automatically skips .git, node_modules, __pycache__, and similar directories.
            - 多个无依赖的搜索请求可以并行发起。""";

    @Override
    public String name() {
        return "Grep";
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
        var properties = new java.util.LinkedHashMap<String, Object>();
        properties.put("pattern", Map.of("type", "string", "description", "Regex pattern to search for"));
        properties.put("path", Map.of("type", "string", "description", "Base directory to search from", "default", "."));
        properties.put("include", Map.of("type", "string", "description", "Glob filter for filenames (e.g. '*.py')"));

        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", properties,
                        "required", List.of("pattern")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String pattern = stringArg(args, "pattern", "");
        String basePath = stringArg(args, "path", ".");
        String include = stringArg(args, "include", "");
        if (basePath.isEmpty()) {
            basePath = ".";
        }
        if (pattern.isEmpty()) {
            return ToolResult.error("Error: pattern is required");
        }

        Path root = Path.of(basePath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return ToolResult.error("Error: path not found: " + basePath);
        }

        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("Error: invalid regex: " + e.getMessage());
        }

        PathMatcher includeMatcher = include.isEmpty()
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + include);

        var files = new ArrayList<Path>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (includeMatcher != null && !includeMatcher.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return ToolResult.error("Error: " + e.getMessage());
        }

        Collections.sort(files);

        var results = new ArrayList<String>();
        int totalChars = 0;

        for (Path file : files) {
            if (isBinaryFile(file)) {
                continue;
            }

            try (BufferedReader reader = Files.newBufferedReader(file)) {
                String line;
                int lineNum = 0;
                while ((line = reader.readLine()) != null) {
                    lineNum++;
                    if (regex.matcher(line).find()) {
                        String rel = root.relativize(file).toString();
                        String entry = rel + ":" + lineNum + ":" + line;
                        totalChars += entry.length() + 1;
                        if (totalChars > MAX_MATCH_CHARS) {
                            results.add("... output truncated (max " + MAX_MATCH_CHARS + " chars)");
                            return ToolResult.success(String.join("\n", results));
                        }
                        results.add(entry);
                    }
                }
            } catch (IOException e) {
                // 跳过读不了的文件
            }
        }

        if (results.isEmpty()) {
            return ToolResult.success("No matches found.");
        }
        return ToolResult.success(String.join("\n", results));
    }

    /**
     * 读取前 512 字节，含空字节则视为二进制文件跳过。
     */
    private static boolean isBinaryFile(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buf = new byte[512];
            int bytesRead = is.read(buf);
            if (bytesRead <= 0) {
                return false;
            }
            for (int i = 0; i < bytesRead; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }
}
