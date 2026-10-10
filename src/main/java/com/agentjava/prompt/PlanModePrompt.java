package com.agentjava.prompt;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plan Mode 提示词。
 *
 * <p>Plan Mode 不再拼进 System Prompt（否则每次都让 Prompt Cache 失效），
 * 而是按轮次以 system-reminder 的形式注入 messages：第 1 轮发完整版，之后每隔
 * {@link #REMINDER_INTERVAL} 轮重复完整版，其余轮次只发一句精简提醒。
 *
 * <p>四种模板覆盖 Plan Mode 生命周期：完整版、精简版、重入版、退出版。
 * planPath 指向计划文件，让模型知道把方案写在哪里、是否已有旧方案可增量修改。
 */
public final class PlanModePrompt {

    private PlanModePrompt() {
    }

    /** 完整版与精简版之间的轮次间隔。 */
    public static final int REMINDER_INTERVAL = 5;

    /** 计划文件相对工作目录的路径（隐藏目录，避免污染项目根）。 */
    private static final String PLAN_RELATIVE = ".agentjava" + java.io.File.separator + "PLAN.md";

    /** 计算 Plan 文件路径。 */
    public static String getPlanPath(String workDir) {
        return Path.of(workDir == null ? System.getProperty("user.dir") : workDir, PLAN_RELATIVE).toString();
    }

    /** 计划文件是否已存在。 */
    public static boolean planExists(String planPath) {
        try {
            return planPath != null && Files.exists(Path.of(planPath));
        } catch (Exception ignored) {
            return false;
        }
    }

    static final String PLAN_MODE_FULL_REMINDER = """
            你当前处于计划模式（Plan Mode）。在计划模式下，你只能进行探索与分析
            （读取文件、搜索代码、浏览项目结构），并把实现计划清晰地输出给用户。
            不要调用任何写操作或命令执行类工具——它们已经被禁用。

            五阶段工作流：
            1. 理解需求：搞清楚用户到底想要什么，澄清模糊点。
            2. 设计方案：基于现有代码结构给出最小可行的修改方案。
            3. 审查：检查方案是否覆盖所有需求、是否引入了不必要的改动。
            4. 写最终计划：把方案写入计划文件（路径见下），用清晰的小节组织。
            5. 退出：告诉用户计划已写好，等待其确认后再真正动手。

            %s""";

    static final String PLAN_MODE_SPARSE_REMINDER = """
            你仍处于计划模式（Plan Mode）。只探索、只规划，不要执行写操作或命令。
            完整说明见你之前收到的 Plan Mode 指令；计划文件路径：%s""";

    static final String PLAN_MODE_REENTRY_REMINDER = """
            你重新进入了计划模式（Plan Mode）。请先读取已有的计划文件（%s），
            判断是继续修改还是重新开始，再继续工作。""";

    static final String PLAN_MODE_EXIT_REMINDER = """
            计划模式已结束，你现在可以正常执行读写与命令操作。%s""";

    /**
     * 按当前轮次构造提醒：第 1、6、11、16…轮发完整版，其余发精简版。
     *
     * @param planPath     计划文件路径
     * @param planExists   计划文件是否已存在（决定提示「读取」还是「创建」）
     * @param iteration    当前循环轮次（从 1 开始）
     */
    public static String buildReminder(String planPath, boolean planExists, int iteration) {
        String planFileInfo = buildPlanFileInfo(planPath, planExists);
        if ((iteration - 1) % REMINDER_INTERVAL == 0) {
            return String.format(PLAN_MODE_FULL_REMINDER, planFileInfo);
        }
        return String.format(PLAN_MODE_SPARSE_REMINDER, planPath);
    }

    /** 用户重新进入 Plan Mode 时调用。 */
    public static String buildReentryReminder(String planPath) {
        return String.format(PLAN_MODE_REENTRY_REMINDER, planPath);
    }

    /** 用户退出 Plan Mode 时调用。 */
    public static String buildExitReminder(String planPath, boolean planExists) {
        String extra = planExists
                ? " 计划文件位于 " + planPath + "，如需参考可随时查看。"
                : "";
        return String.format(PLAN_MODE_EXIT_REMINDER, extra);
    }

    private static String buildPlanFileInfo(String planPath, boolean planExists) {
        String info = "Plan file: " + planPath;
        if (planExists) {
            info += "\nA plan file already exists at " + planPath
                    + ". You can read it and make incremental edits using the EditFile tool.";
        } else {
            info += "\nNo plan file exists yet. You should create your plan at "
                    + planPath + " using the WriteFile tool.";
        }
        return info;
    }
}
