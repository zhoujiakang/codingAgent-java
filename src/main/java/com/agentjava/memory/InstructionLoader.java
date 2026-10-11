package com.agentjava.memory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 项目指令加载器：负责项目指令文件的发现、拼接与 @include 递归展开。
 *
 * <p><b>发现顺序从低优先级到高</b>：先扫用户全局目录 {@code ~/.agentjava/}，
 * 再从 git root 向下逐层扫项目目录（每层找 {@code AGENTJAVA.md}、{@code AGENTS.md}
 * 以及 {@code .agentjava/AGENTJAVA.md}），最后才是 {@code AGENTJAVA.local.md}
 * （本地私有，通常不进版本库）。
 *
 * <p><b>所有找到的文件全部拼接，用 {@code ---} 分隔线连接，不是找到一个就返回。</b>
 * 低优先级的排在前面，高优先级的排在后面——LLM 对后出现的内容更敏感，
 * 有冲突时项目级和本地私有文件说了算。
 *
 * <p>指令文件名使用 {@code AGENTJAVA.md}（与 {@code .agentjava/} 配置目录、
 * {@code com.agentjava} 包名一致），同时兼容跨工具通用的 {@code AGENTS.md}。
 */
public final class InstructionLoader {

    /** 项目指令文件名。 */
    public static final String INSTRUCTION_FILE = "AGENTJAVA.md";

    /** 跨工具通用的指令文件名，与 AGENTJAVA.md 同级发现。 */
    public static final String AGENTS_FILE = "AGENTS.md";

    /** 本地私有覆盖文件。 */
    public static final String LOCAL_FILE = "AGENTJAVA.local.md";

    /** @include 递归深度上限，防止引用链过深或成环时无限展开。 */
    public static final int MAX_INCLUDE_DEPTH = 5;

    private InstructionLoader() {
    }

    /** 一个被采纳的指令来源及其（已展开的）内容。 */
    public record InstructionSource(String path, String content) {
    }

    /**
     * 加载全部指令并拼成一段文本。
     *
     * @param workDir 当前工作目录
     * @return 拼接后的指令文本；没有任何指令文件时返回空串
     */
    public static String loadInstructions(String workDir) {
        List<InstructionSource> sources = discoverInstructions(workDir);
        if (sources.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) {
                sb.append("\n\n---\n\n");
            }
            sb.append(sources.get(i).content().strip());
        }
        return sb.toString();
    }

    /**
     * 按优先级从低到高发现全部指令文件。
     *
     * <p>{@code seen} 集合防止同一个绝对路径被重复加载：monorepo 场景下往上找 git root
     * 可能和往下遍历的路径重合，去重之后再拼，避免同一份指令出现两遍。
     */
    public static List<InstructionSource> discoverInstructions(String workDir) {
        if (workDir == null || workDir.isBlank()) {
            return List.of();
        }
        var sources = new ArrayList<InstructionSource>();
        var seen = new HashSet<String>();

        // 1. 用户全局指令
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            addSource(sources, seen, Path.of(home, ".agentjava", INSTRUCTION_FILE), workDir);
            addSource(sources, seen, Path.of(home, ".agentjava", AGENTS_FILE), workDir);
        }

        // 2. 项目级：从 git root 到 workDir 的每一级目录
        for (String dir : projectInstructionDirs(workDir)) {
            addSource(sources, seen, Path.of(dir, INSTRUCTION_FILE), workDir);
            addSource(sources, seen, Path.of(dir, AGENTS_FILE), workDir);
            // .agentjava/ 下的同名文件：想让指令进 .gitignore 的项目放这里
            addSource(sources, seen, Path.of(dir, ".agentjava", INSTRUCTION_FILE), workDir);
        }

        // 3. 本地私有覆盖
        addSource(sources, seen, Path.of(workDir, LOCAL_FILE), workDir);

        return sources;
    }

    private static void addSource(List<InstructionSource> sources, Set<String> seen,
                                  Path path, String workDir) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) {
                return;
            }
            String abs = normalized.toString();
            if (!seen.add(abs)) {
                return;
            }
            String content = Files.readString(normalized, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return;
            }
            String parent = normalized.getParent() == null
                    ? workDir : normalized.getParent().toString();
            sources.add(new InstructionSource(abs,
                    expandIncludes(content, parent, new HashSet<>(), 0, workDir)));
        } catch (IOException e) {
            // 单个文件读不了不影响其余来源
        }
    }

    /**
     * 从 workDir 向上找到 git root，再收集从 git root 到 workDir 的每一级目录。
     *
     * <p>这样 monorepo 的子目录也能有自己的指令文件：越靠近 workDir 的层级越靠后，
     * 也就是优先级越高，恰好符合「子目录应该能覆盖仓库根部的约定」。
     */
    public static List<String> projectInstructionDirs(String workDir) {
        Path start;
        try {
            start = Path.of(workDir).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return List.of();
        }
        Path gitRoot = null;
        Path cur = start;
        while (cur != null) {
            if (Files.isDirectory(cur.resolve(".git"))) {
                gitRoot = cur;
                break;
            }
            Path parent = cur.getParent();
            if (parent == null || parent.equals(cur)) {
                break;
            }
            cur = parent;
        }
        Path top = gitRoot != null ? gitRoot : start;

        var collected = new ArrayList<Path>();
        Path p = start;
        while (p != null) {
            collected.add(p);
            if (p.equals(top)) {
                break;
            }
            Path parent = p.getParent();
            if (parent == null || parent.equals(p)) {
                break;
            }
            p = parent;
        }
        Collections.reverse(collected);
        return collected.stream().map(Path::toString).toList();
    }

    // ───────────────────────── @include 递归展开 ─────────────────────────

    /**
     * 递归展开 {@code @path} 引用。
     *
     * <p>三道防线：{@link #MAX_INCLUDE_DEPTH} 防递归太深，{@code seen} 集合防循环引用，
     * 越界的路径不展开而是留一句 HTML 注释占位。
     *
     * <p><b>代码块内的 {@code @xxx} 不会被引擎当成引用</b>：用 {@code inCode} 布尔值
     * 追踪反引号围栏状态。文档里经常要写 {@code @Override}、{@code @Bean} 这类注解，
     * 如果代码块里也展开，一篇讲 Spring 的规范文档就会炸掉。
     *
     * @param root 项目根目录；用于越界检查，为 null 时不做该检查
     */
    static String expandIncludes(String content, String baseDir, Set<String> seen, int depth) {
        return expandIncludes(content, baseDir, seen, depth, null);
    }

    static String expandIncludes(String content, String baseDir, Set<String> seen,
                                 int depth, String root) {
        if (depth > MAX_INCLUDE_DEPTH) {
            return content;
        }
        var out = new StringBuilder();
        boolean inCode = false;
        try (var reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("```")) {
                    inCode = !inCode;
                    out.append(line).append('\n');
                    continue;
                }
                if (!inCode) {
                    String includePath = parseInclude(trimmed);
                    if (includePath != null) {
                        String resolved = resolveInclude(includePath, baseDir);
                        String absPath = normalize(resolved);
                        Path target = safePath(absPath);
                        if (target == null) {
                            out.append("<!-- Failed to include ").append(includePath)
                                    .append(": outside project -->\n");
                            continue;
                        }
                        if (!isAllowed(target, root)) {
                            out.append("<!-- Failed to include ").append(includePath)
                                    .append(": outside project -->\n");
                            continue;
                        }
                        if (!seen.contains(absPath)) {
                            String data;
                            try {
                                data = Files.readString(target, StandardCharsets.UTF_8);
                            } catch (IOException e) {
                                out.append("<!-- Failed to include ").append(includePath)
                                        .append(": ").append(e.getMessage()).append(" -->\n");
                                continue;
                            }
                            seen.add(absPath);
                            out.append(expandIncludes(data, target.getParent().toString(),
                                    seen, depth + 1, root));
                            continue;
                        }
                    }
                }
                out.append(line).append('\n');
            }
        } catch (IOException e) {
            return content;
        }
        return out.toString();
    }

    /**
     * 识别 {@code @} 前缀引用，返回其中的路径部分。
     *
     * <p>只接受以 {@code ./}、{@code ../}、{@code ~/}、{@code /} 开头的路径，
     * 其它 {@code @token}（比如 {@code @username}）一律返回 null，避免误判。
     */
    static String parseInclude(String trimmed) {
        if (!trimmed.startsWith("@")) {
            return null;
        }
        String rest = trimmed.substring(1).trim();
        if (rest.isEmpty()) {
            return null;
        }
        if (rest.startsWith("./") || rest.startsWith("../")
                || rest.startsWith("~/") || rest.startsWith("/")) {
            return rest;
        }
        return null;
    }

    /** 解析三种路径形式：{@code ~/} 展开为 HOME，绝对路径原样返回，相对路径基于包含文件所在目录。 */
    static String resolveInclude(String includePath, String baseDir) {
        if (includePath.startsWith("~/")) {
            String home = System.getProperty("user.home");
            if (home == null) {
                return includePath;
            }
            return Path.of(home, includePath.substring(2)).toString();
        }
        if (includePath.startsWith("/")) {
            return includePath;
        }
        if (baseDir == null || baseDir.isEmpty()) {
            return includePath;
        }
        return Path.of(baseDir, includePath).toString();
    }

    private static String normalize(String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException e) {
            return path;
        }
    }

    private static Path safePath(String absPath) {
        try {
            return Path.of(absPath);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * 引用必须落在允许范围内：项目目录树内部，或者用户级的 {@code ~/.agentjava} 目录内部。
     *
     * <p>越界的引用不会被静默展开，而是留一句 HTML 注释——这样既防止指令文件把
     * {@code /etc/passwd} 这类敏感文件拉进上下文，也让模型的输出里能看到"这里少了一段"。
     */
    private static boolean isAllowed(Path target, String root) {
        if (root == null || root.isBlank()) {
            return true;
        }
        Path rootPath = safePath(normalize(root));
        if (rootPath == null) {
            return true;
        }
        if (target.startsWith(rootPath)) {
            return true;
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            Path userDir = Path.of(home, ".agentjava");
            if (target.startsWith(userDir)) {
                return true;
            }
        }
        return false;
    }
}
