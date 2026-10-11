package com.agentjava.subagent;

import com.agentjava.permission.PermissionMode;

import java.util.List;
import java.util.Locale;

/**
 * Agent 定义：一个子 Agent 角色在内存里的形态。
 *
 * <p>即 {@code AgentDefinition}。定义文件是 Markdown + YAML frontmatter，
 * frontmatter 里的 {@code name} / {@code description} 加载后映射成 {@link #agentType} /
 * {@link #whenToUse}——之所以要换名字，是因为这两个字段在 Agent 语境下的含义跟文件里
 * 的键名不完全一致：{@code name} 是 {@code subagent_type} 的取值（是个 ID），
 * {@code description} 是给主 Agent 看的「什么时候该派它」（是个判断依据）。
 *
 * <p>和 Skill 定义的结构（YAML frontmatter + Markdown body）相同，语义不同：
 * <ul>
 *   <li>Skill 的 body 是注入当前对话的 SOP 指令，作为 LoadSkill 的 tool result 进入历史；</li>
 *   <li>Agent 的 body 是子 Agent 的<b>系统提示</b>，决定「它是谁、能做什么、按什么风格干活」，
 *       伴随子 Agent 整个生命周期。</li>
 * </ul>
 *
 * <p>能力边界用两个字段表达：{@code tools} 白名单确定范围，{@code disallowedTools}
 * 黑名单从中再排除。黑名单用得更多——Agent 扮演一个角色，能力接近全集，
 * 逐个列出几十个允许的工具不现实，排除少数危险工具更方便。
 *
 * <p>三个内置 Agent 以常量形式硬编码在这里（不依赖 .md 文件），
 * 项目级 / 用户级 / 插件级的同名定义可以覆盖它们。
 */
public record AgentDefinition(String agentType,
                              String whenToUse,
                              List<String> tools,
                              List<String> disallowedTools,
                              String model,
                              int maxTurns,
                              PermissionMode permissionMode,
                              String systemPrompt,
                              String filePath,
                              String source,
                              String isolation) {

    /** 文件系统隔离模式：worktree（Git 工作树隔离）。为 null 表示不隔离。 */
    public static final String ISOLATION_WORKTREE = "worktree";

    /** 定义里没写 maxTurns 时的兜底轮数。 */
    public static final int DEFAULT_MAX_TURNS = 200;

    // ───────────────────────── 来源标签 ─────────────────────────

    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_USER = "user";
    public static final String SOURCE_PROJECT = "project";
    public static final String SOURCE_PLUGIN = "plugin";

    // ───────────────────────── 内置 Agent ─────────────────────────

    private static final String GENERAL_PURPOSE_PROMPT = """
            你是 AgentJava 的通用 Agent。根据调用方给你的任务，使用可用工具把它做完。
            把任务做完，不要过度设计，但也不要做一半就停。

            完成后用简洁的报告回复：做了什么、关键发现。
            调用方会把结果转述给用户，所以只需要包含要点，不要寒暄、不要复述任务。

            搜索策略：不确定位置时广泛搜索，确定路径时直接读取。
            优先编辑现有文件，不要主动创建文档文件。""";

    private static final String PLAN_PROMPT = """
            你是一个软件架构师和规划专家。这是一个只读规划任务。

            严禁：创建文件、修改文件、删除文件、执行任何改变系统状态的命令。

            你的工作流程：
            1. 理解需求，明确设计视角
            2. 用搜索工具充分探索代码库：找到现有模式和约定，理解当前架构，识别可参考的类似功能
            3. 设计方案：制定实现路径，考虑取舍和架构决策
            4. 输出计划：提供分步实现策略，标明依赖和顺序，预判潜在挑战

            回复末尾必须列出 3-5 个对实现最关键的文件路径。""";

    private static final String EXPLORE_PROMPT = """
            你是一个文件搜索专家。这是一个只读探索任务。

            严禁：创建文件、修改文件、删除文件、执行任何改变系统状态的命令。

            你的工具使用策略：
            - 用 Glob 做文件模式匹配
            - 用 Grep 搜索文件内容
            - 用 ReadFile 读取已知路径的文件
            - Bash 只用于只读操作（ls、git log、git diff、find、cat）
            - 尽可能并行发起多个工具调用以提高效率

            高效完成搜索请求，清晰报告发现。""";

    /** 全能力通用 Agent：不限制工具，200 轮上限，继承父 Agent 的模型与权限模式。 */
    public static final AgentDefinition GENERAL_PURPOSE = new AgentDefinition(
            "general-purpose",
            "通用子 Agent，拥有完整工具权限，适合需要独立上下文的多步骤复杂任务",
            List.of(),
            List.of(),
            null,
            0,
            null,
            GENERAL_PURPOSE_PROMPT,
            null,
            SOURCE_BUILTIN,
            null);

    /**
     * 计划 Agent：只读规划，15 轮上限。
     *
     * <p>双重保障：{@code disallowedTools} 在工具层面禁止写操作，系统提示在提示词层面
     * 也强调「严禁修改文件」。它和 Plan <b>权限模式</b>是两回事——后者是主 Agent
     * 自己切到只读状态，不创建子 Agent；这里是独立的只读子 Agent，规划过程完全隔离。
     */
    public static final AgentDefinition PLAN = new AgentDefinition(
            "plan",
            "软件架构 Agent，负责设计实现方案、分析权衡，产出分步计划（只读）",
            List.of(),
            List.of("Agent", "EditFile", "WriteFile"),
            null,
            15,
            null,
            PLAN_PROMPT,
            null,
            SOURCE_BUILTIN,
            null);

    /**
     * 探索 Agent：只读搜索，30 轮上限，用 haiku 小模型。
     *
     * <p>用黑名单而不是白名单，是为了让后加入的只读工具自动可用；Explore 用黑名单之所以
     * 安全，是因为写操作类（EditFile / WriteFile）已经被排除掉了。
     * 模型选 haiku 是因为搜索和阅读不需要最强推理，能省下可观的 token 成本。
     */
    public static final AgentDefinition EXPLORE = new AgentDefinition(
            "explore",
            "只读搜索 Agent，用 haiku 小模型快速定位代码，适合需要 3 次以上查询的探索任务",
            List.of(),
            List.of("EditFile", "WriteFile"),
            "haiku",
            30,
            null,
            EXPLORE_PROMPT,
            null,
            SOURCE_BUILTIN,
            null);

    public AgentDefinition {
        tools = tools == null ? List.of() : List.copyOf(tools);
        disallowedTools = disallowedTools == null ? List.of() : List.copyOf(disallowedTools);
        if (maxTurns < 0) {
            maxTurns = 0;
        }
        // isolation 归一化：空白 → null，其余转小写，便于后续 == 比较
        if (isolation != null) {
            isolation = isolation.strip().toLowerCase(Locale.ROOT);
            if (isolation.isBlank()) {
                isolation = null;
            }
        }
    }

    /** 三个内置 Agent，按此顺序注册（同名覆盖发生在加载器里）。 */
    public static List<AgentDefinition> builtins() {
        return List.of(GENERAL_PURPOSE, PLAN, EXPLORE);
    }

    /** 定义里没写 maxTurns（0 或负）时用兜底轮数。 */
    public int effectiveMaxTurns() {
        return maxTurns > 0 ? maxTurns : DEFAULT_MAX_TURNS;
    }

    /** 是否自定义 Agent（来自 .md 文件）。自定义 Agent 要额外过一层工具禁用列表。 */
    public boolean isCustom() {
        return !SOURCE_BUILTIN.equals(source);
    }

    /**
     * 把 frontmatter 里的权限模式字符串映射成 {@link PermissionMode}。
     *
     * <p>存在两种命名体系：{@code dontAsk} / {@code bypassPermissions} 都指
     * 「工具调用不弹审批窗口」，所以一并映射到 {@link PermissionMode#BYPASS}。
     *
     * @return 映射结果；空值或无法识别时返回 {@code null}，表示继承父 Agent 的权限模式
     */
    public static PermissionMode parsePermissionMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "dontask", "dont_ask", "bypasspermissions", "bypass", "yolo" -> PermissionMode.BYPASS;
            case "acceptedits", "accept_edits", "accept" -> PermissionMode.ACCEPT_EDITS;
            case "plan" -> PermissionMode.PLAN;
            case "default" -> PermissionMode.DEFAULT;
            default -> null;
        };
    }
}
