package com.agentjava.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 记忆目录扫描器：并发扫描记忆文件、解析 YAML frontmatter、构建 manifest。
 *
 * <p>扫描放在这里而不是 {@link MemoryManager} 里，是因为召回选择器也需要同一套逻辑
 * （按 mtime 判断新鲜度、按 filePath 回头读全文），两条路径共用一份实现。
 */
public final class MemoryScanner {

    /** 索引文件名。它本身是索引，不能被当成一条记忆扫进来，所以扫描时要排除。 */
    public static final String ENTRYPOINT_NAME = "MEMORY.md";

    private static final Set<String> VALID_TYPES = Set.of("user", "feedback", "project", "reference");

    private MemoryScanner() {
    }

    /** 中间解析结果：frontmatter 里的三个字段。 */
    public record Frontmatter(String name, String description, String type) {
    }

    /**
     * 记忆文件的元数据视图，服务于召回选择器。
     *
     * @param scope       "user" 或 "project"，区分该文件属于哪个目录
     * @param mtimeMs     文件修改时间，用于判断这条记忆是否过期
     * @param filePath    绝对路径，选中之后要回头读全文
     * @param description frontmatter 里的 description，缺失时为 ""
     * @param type        frontmatter 里的 type，不合法时为 ""
     */
    public record MemoryHeader(String filename, String filePath, String scope, long mtimeMs,
                               String description, String type) {
    }

    /** 类型校验：只认四个合法值，其它一律返回 false。 */
    public static boolean isValidType(String type) {
        return type != null && VALID_TYPES.contains(type.toLowerCase(Locale.ROOT));
    }

    /**
     * 解析 YAML frontmatter。文件不是以 {@code ---} 开头的，视为没有 frontmatter。
     *
     * <p>这里刻意做扁平扫描而不是逐段解析嵌套结构：frontmatter 里只有一个
     * {@code metadata:} 子块，且其中只关心 {@code type}，扁平取 key 既够用也更耐受
     * 手写文件时缩进不一致的情况。
     */
    public static Frontmatter parseFrontmatter(String content) {
        String name = "";
        String description = "";
        String type = "";
        if (content == null || content.isEmpty()) {
            return new Frontmatter(name, description, type);
        }
        String[] lines = content.split("\n", -1);
        if (!lines[0].trim().equals("---")) {
            return new Frontmatter(name, description, type);
        }
        for (int i = 1; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.equals("---")) {
                break;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            switch (key) {
                case "name" -> name = value;
                case "description" -> description = value;
                case "type" -> type = value;
                default -> {
                }
            }
        }
        return new Frontmatter(name, description, isValidType(type) ? type : "");
    }

    /**
     * 并发扫描一个记忆目录。
     *
     * <p>并发是有意义的：每条记忆都要读文件 + 解析 frontmatter，目录里几十条记忆时
     * 串行扫描的延迟能被用户感知，而这一步发生在每轮 Loop 完成的路径上。
     *
     * @param dir  记忆目录
     * @param scope 该目录对应的作用域标记（"user" / "project"）
     */
    public static List<MemoryHeader> scanMemoryFiles(Path dir, String scope) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".md") && !n.equals(ENTRYPOINT_NAME);
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
        return files.parallelStream()
                .map(p -> {
                    Frontmatter fm;
                    long mtime;
                    try {
                        fm = parseFrontmatter(Files.readString(p, StandardCharsets.UTF_8));
                        mtime = Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        return null;
                    }
                    return new MemoryHeader(p.getFileName().toString(),
                            p.toAbsolutePath().toString(), scope, mtime,
                            fm.description(), fm.type());
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(MemoryHeader::filename))
                .toList();
    }

    /**
     * 构建给 LLM 看的清单。
     *
     * <p>召回和去重两个场景都用得上，格式刻意压到一行一条：这部分内容每轮都要发给模型，
     * 多一个字就是一份持续付出的 token 成本。
     */
    public static String buildManifest(List<MemoryHeader> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        for (MemoryHeader h : headers) {
            String type = h.type().isEmpty() ? "unknown" : h.type();
            sb.append("- ").append(h.filename()).append(" [").append(type).append("] ");
            sb.append(h.description().isEmpty() ? "(no description)" : h.description());
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 收集一个目录下的全部记忆文件（含解析开销），供列表展示使用。 */
    static List<MemoryFile> loadDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> mdFiles;
        try (Stream<Path> stream = Files.list(dir)) {
            mdFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".md") && !n.equals(ENTRYPOINT_NAME);
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
        var out = new ArrayList<MemoryFile>(mdFiles.size());
        for (Path fp : mdFiles) {
            try {
                String content = Files.readString(fp, StandardCharsets.UTF_8);
                var fm = parseFrontmatter(content);
                out.add(new MemoryFile(fp.toAbsolutePath().toString(),
                        fp.getFileName().toString(), fm.name(), fm.description(), fm.type()));
            } catch (IOException e) {
                // 单个文件读不动就跳过
            }
        }
        return out;
    }
}
