package com.agentjava.subagent;

import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolRegistry;

import java.util.HashSet;
import java.util.Set;

/**
 * 子 Agent 的工具过滤：多层防线，每层防不同的风险。
 *
 * <p>为什么需要过滤？如果子 Agent 能用全部工具（包括 Agent 工具本身），A 可以派 B、
 * B 可以派 C，无限嵌套下去，上下文与 token 都会爆炸；如果后台 Agent 也能随意调用
 * 任何工具，一个失控的后台任务可能造成不可预期的后果。
 *
 * <p>四层依次过滤（另有 MCP 直通与队友放行两层，此处不涉及）：
 * <ol>
 *   <li><b>全局禁止</b> {@link #ALL_AGENT_DISALLOWED_TOOLS}：所有子 Agent 都不能用。
 *       挡掉 Agent（防递归失控）与 AskUserQuestion（防阻塞循环）等；</li>
 *   <li><b>自定义限制</b> {@link #CUSTOM_AGENT_DISALLOWED_TOOLS}：用户或项目定义的
 *       Agent 额外收紧一层，内置 Agent 不受影响。两层内容目前相同，分开维护是为了
 *       将来收得更紧时不必改到内置路径；</li>
 *   <li><b>后台白名单</b> {@link #ASYNC_AGENT_ALLOWED_TOOLS}：后台 Agent 只能用基础读写工具。
 *       白名单里<b>不包含 Agent 工具</b>，从根源上切断后台嵌套；</li>
 *   <li><b>定义层</b>：先按 {@code disallowedTools} 黑名单排除，再与 {@code tools}
 *       白名单取交集。白名单为空（或只有 {@code *}）时跳过交集这一步。</li>
 * </ol>
 *
 * <p><b>Fork 路径不走过滤</b>：Fork 子 Agent 直接复制父 Agent 的全部工具（
 * {@link #cloneForFork}），保持工具定义与父 Agent 字节一致以命中 prompt cache；
 * 防递归靠调用那一刻的运行时拦截（AgentTool 的 querySource 标记）。
 */
public final class ToolFilter {

    /**
     * 全局禁止：任何子 Agent 都拿不到。
     *
     * <p>列表里包含本项目尚未实现的工具名（TaskOutput / ExitPlanMode / EnterPlanMode /
     * AskUserQuestion / TaskStop / Workflow），是刻意的——这一层是「能力黑名单」，
     * 将来工具集扩充时不用回头改这里。
     */
    public static final Set<String> ALL_AGENT_DISALLOWED_TOOLS = Set.of(
            "TaskOutput", "ExitPlanMode", "EnterPlanMode",
            "Agent", "AskUserQuestion", "TaskStop", "Workflow");

    /** 自定义 Agent 的额外禁用集，目前与全局禁用集相同，独立维护以便将来收得更紧。 */
    public static final Set<String> CUSTOM_AGENT_DISALLOWED_TOOLS = Set.of(
            "TaskOutput", "ExitPlanMode", "EnterPlanMode",
            "Agent", "AskUserQuestion", "TaskStop", "Workflow");

    /**
     * 后台 Agent 白名单：本项目的基础读写工具集。
     *
     * <p>注意这里<b>没有 Agent</b>——后台子 Agent 不能再派子 Agent，这是「后台层防资源失控」
     * 与「全局层防递归失控」两条原则叠加后的必然结果。
     */
    public static final Set<String> ASYNC_AGENT_ALLOWED_TOOLS = Set.of(
            "ReadFile", "WriteFile", "EditFile", "Bash", "Glob", "Grep", "LoadSkill");

    /**
     * teammate 在协作工具之外额外被挡掉的工具。组建和解散团队由 Lead 负责，
     * teammate 只管干活和相互协调，不参与团队成员管理。
     */
    public static final Set<String> TEAMMATE_DISALLOWED_TOOLS = Set.of("TeamCreate", "TeamDelete");

    private ToolFilter() {
    }

    /** 最常见的同步内置 Agent 场景：非后台、非自定义。 */
    public static ToolRegistry filterForAgent(ToolRegistry source, AgentDefinition def) {
        return filterForAgent(source, def, false);
    }

    /**
     * teammate 专用过滤：在常规子 Agent 规则之上，再挡掉团队成员管理工具。
     *
     * <p>{@code Agent} 由 {@link #ALL_AGENT_DISALLOWED_TOOLS} 在更前面一层挡掉，
     * 所以 teammate 不会再往下派子 Agent。
     */
    public static ToolRegistry filterForTeammate(ToolRegistry source, AgentDefinition def) {
        ToolRegistry filtered = new ToolRegistry();
        for (Tool tool : filterForAgent(source, def).listTools()) {
            if (TEAMMATE_DISALLOWED_TOOLS.contains(tool.name())) continue;
            filtered.register(tool);
        }
        return filtered;
    }

    /**
     * 按定义过滤出一份子 Agent 专用的 {@link ToolRegistry}。
     *
     * @param source  父 Agent 的工具注册中心
     * @param def     子 Agent 定义
     * @param isAsync 是否后台执行（应用后台白名单）
     */
    public static ToolRegistry filterForAgent(ToolRegistry source, AgentDefinition def, boolean isAsync) {
        boolean isCustom = def != null && def.isCustom();
        Set<String> disallowed = def == null ? Set.of() : new HashSet<>(def.disallowedTools());
        boolean hasWhitelist = def != null && def.tools() != null && !def.tools().isEmpty()
                && !(def.tools().size() == 1 && "*".equals(def.tools().get(0)));
        Set<String> allowed = hasWhitelist ? new HashSet<>(def.tools()) : Set.of();

        ToolRegistry filtered = new ToolRegistry();
        for (Tool tool : source.listTools()) {
            String name = tool.name();

            // 第 1 层：全局禁止
            if (ALL_AGENT_DISALLOWED_TOOLS.contains(name)) {
                continue;
            }
            // 第 2 层：自定义 Agent 额外限制
            if (isCustom && CUSTOM_AGENT_DISALLOWED_TOOLS.contains(name)) {
                continue;
            }
            // 第 3 层：后台白名单
            if (isAsync && !ASYNC_AGENT_ALLOWED_TOOLS.contains(name)) {
                continue;
            }
            // 第 4 层：定义层黑名单 → 白名单交集
            if (disallowed.contains(name)) {
                continue;
            }
            if (hasWhitelist && !allowed.contains(name)) {
                continue;
            }
            filtered.register(tool);
        }
        return filtered;
    }

    /**
     * Fork 专用：复制父注册表的全部工具，不做任何过滤。
     *
     * <p>遇到 AgentTool 时浅复制并把 {@code querySource} 标成 fork，
     * 使 fork 子 Agent 不能再 fork（运行时拦截），同时保持工具定义与父 Agent
     * 字节一致——这是 Fork 能命中 prompt cache 的前提。
     */
    public static ToolRegistry cloneForFork(ToolRegistry source) {
        ToolRegistry forked = new ToolRegistry();
        for (Tool tool : source.listTools()) {
            if (tool instanceof AgentTool at) {
                forked.register(at.cloneWithQuerySource(AgentTool.FORK_QUERY_SOURCE));
            } else {
                forked.register(tool);
            }
        }
        return forked;
    }
}
