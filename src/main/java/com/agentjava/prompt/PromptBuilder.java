package com.agentjava.prompt;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 七模块 System Prompt 组装器。
 *
 * <p>核心思路：System Prompt 是 Agent 的「驾驶手册」，不能只有三行。
 * 它应当按职责拆成固定模块，每个模块解决一个具体问题（你是谁、怎么沟通、
 * 怎么用工具、代码写成什么样、安全红线、不同任务怎么处理、输出风格），
 * 再用固定的优先级拼起来。稳定内容放进 system 通道，既清晰又利于 Prompt Cache 命中。
 *
 * <p>本类只负责「组装逻辑」：Section 的收集、按 priority 排序、空内容剔除、拼接。
 * 每个模块的文本内容由 {@link PromptSections} 提供，职责分离。
 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    /**
     * 提示词片段：不可变三段式。
     *
     * <p>name 标识身份，priority 决定排序权重（数字越小越靠前），
     * content 承载实际文本。record 自动生成构造器与访问器，创建后不可篡改。
     */
    public record Section(String name, int priority, String content) {
    }

    /**
     * 运行时环境快照。把工作目录、操作系统、Shell、Git 状态等打包成不可变对象传递，
     * 比在函数签名里堆八个参数更清晰，也方便测试时直接构造假上下文。
     */
    public record EnvironmentContext(
            String workDir,
            String os,
            String arch,
            String shell,
            boolean isGitRepo,
            String gitBranch,
            String model
    ) {
    }

    /**
     * 可选的外部注入。skillSection 是 Skill 系统激活后注入到 System Prompt 的技能描述。
     * 项目指令（AGENTJAVA.md）与自动记忆不走这里——它们包成 system-reminder 进 messages。
     */
    public record BuildOptions(String skillSection) {
    }

    /** 七个固定模块，按文档固定的优先级排列（0 ~ 60 间隔 10）。 */
    private static final List<Section> FIXED_SECTIONS = List.of(
            PromptSections.identitySection(),
            PromptSections.systemSection(),
            PromptSections.doingTasksSection(),
            PromptSections.executingActionsSection(),
            PromptSections.usingToolsSection(),
            PromptSections.toneStyleSection(),
            PromptSections.outputEfficiencySection()
    );

    /**
     * 组装完整 System Prompt。
     *
     * @param env     运行时环境快照（注入 Environment 模块）
     * @param options 可选外部注入（如 Skill 描述）；为 null 或空则不追加
     * @return 按优先级排序、空模块剔除、用空行拼接好的 system prompt
     */
    public static String buildSystemPrompt(EnvironmentContext env, BuildOptions options) {
        var builder = new PromptBuilder();
        for (var section : FIXED_SECTIONS) {
            builder.add(section);
        }
        // 条件注入：Skill 描述排在固定模块之后（priority=90）
        if (options != null && options.skillSection() != null && !options.skillSection().isBlank()) {
            builder.add(new Section("Skills", 90, options.skillSection()));
        }
        // 环境段落 priority=70，稳定且每轮不变，便于缓存分层
        builder.add(PromptSections.environmentSection(env));
        return builder.build();
    }

    // ===== 以下为 Builder 内部实现 =====

    private final List<Section> sections = new ArrayList<>();

    /** 追加一个模块，返回 this 支持链式调用。 */
    public PromptBuilder add(Section section) {
        sections.add(section);
        return this;
    }

    /**
     * 排序 + 拼接。排序时机在 build() 调用时，add 的顺序不影响最终结果。
     * 额外做 null/空白剔除，避免空模块在最终文本里留下空行。
     */
    public String build() {
        sections.sort(Comparator.comparingInt(Section::priority));
        var parts = new ArrayList<String>();
        for (Section s : sections) {
            String content = s.content() == null ? "" : s.content().strip();
            if (!content.isEmpty()) {
                parts.add(content);
            }
        }
        return String.join("\n\n", parts);
    }

    /**
     * 探测当前运行时环境。workDir 取 JVM 的 user.dir；Git 状态用 ProcessBuilder 调命令，
     * 命令不存在或不在仓库内时静默忽略（探测失败不应阻断启动）。
     */
    public static EnvironmentContext detectEnvironment(String model) {
        String workDir = System.getProperty("user.dir");
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        String arch = System.getProperty("os.arch", "unknown");
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            shell = "bash";
        }

        boolean isGitRepo = false;
        String gitBranch = null;
        try {
            Process p = new ProcessBuilder("git", "-C", workDir,
                    "rev-parse", "--is-inside-work-tree")
                    .redirectErrorStream(true).start();
            try (var reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if ("true".equals(line != null ? line.strip() : "")) {
                    isGitRepo = true;
                }
            }
            p.waitFor();

            if (isGitRepo) {
                Process b = new ProcessBuilder("git", "-C", workDir,
                        "rev-parse", "--abbrev-ref", "HEAD")
                        .redirectErrorStream(true).start();
                try (var reader = new BufferedReader(
                        new InputStreamReader(b.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null) {
                        line = line.strip();
                        if (!line.isEmpty()) {
                            gitBranch = line;
                        }
                    }
                }
                b.waitFor();
            }
        } catch (Exception ignored) {
            // git 不存在或不在仓库内：保持默认值
        }

        return new EnvironmentContext(workDir, os, arch, shell, isGitRepo, gitBranch, model);
    }
}
