package com.agentjava.skill;

import com.agentjava.conversation.Message;
import com.agentjava.skill.SkillCatalog.Skill;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill 执行器：inline / fork 两种模式，外加参数渲染。
 *
 * <p>两种模式的区别不在「怎么跑」，而在「跑在哪」：
 * <ul>
 *   <li><b>inline</b>：SOP 正文进主对话，模型看得见完整上下文，执行结果也留在历史里。
 *       适合 {@code /commit} 这类要接着当前改动往下干的活；</li>
 *   <li><b>fork</b>：SOP 正文只出现在隔离子 Agent 的对话里，主对话拿到的是它最终的结论。
 *       适合 {@code /review} 这类要客观性的活——之前那句「这样写挺好的」不会因为
 *       留在上下文里就影响这次审查的判断。</li>
 * </ul>
 *
 * <p>Skill 作者在 SKILL.md 里用 {@code mode: fork} 声明意图，两条触发路径
 * （用户敲 {@code /name}、模型自己调 LoadSkill）都遵循它。
 */
public final class SkillExecutor {

    /** forkContext=recent 时带过来的父对话条数。 */
    static final int FORK_RECENT_COUNT = 5;

    private SkillExecutor() {
    }

    /**
     * inline 执行：替换参数 → 登记激活 → 返回渲染后的正文。
     *
     * <p>返回值作为一条消息进入对话，模型下一轮就看到完整 SOP 并开始照着做。
     */
    public static String executeInline(Skill skill, String args, SkillHost host) {
        String body = substituteArguments(skill.promptBody(), args);
        host.activateSkill(skill.meta().name(), body);
        host.recordSkillInvocation(skill.meta().name(), body);
        return body;
    }

    /**
     * fork 执行：正文交给隔离子 Agent，只把它的最终结论带回来。
     *
     * <p>注意这里登记的是<b>未替换参数</b>的原文：子 Agent 那边拿到的才是渲染版，
     * 主对话的恢复记录里存原始 SOP 更合适——参数属于那一次调用，不属于 SOP 本身。
     */
    public static String executeFork(Skill skill, String args, SkillForkHost host) {
        String body = substituteArguments(skill.promptBody(), args);
        host.recordSkillInvocation(skill.meta().name(), skill.promptBody());
        List<Message> seed = buildForkSeed(skill.meta().forkContext(), host.snapshotParentMessages());
        return host.runSubAgent(body, seed, skill.meta().model());
    }

    /**
     * 参数替换，三条路径：
     * <ol>
     *   <li>没传参数 → 原样返回；</li>
     *   <li>正文里有 {@code $ARGUMENTS} → 替换占位符；</li>
     *   <li>正文里没占位符但传了参数 → 追加在末尾。</li>
     * </ol>
     *
     * <p>第三条是刻意的兜底：作者忘了写占位符时，用户输入不会凭空消失，而是被
     * 追加到末尾，模型照样看得见。追加用 {@code ## User Request} 分隔，让模型能
     * 分清哪段是 SOP、哪段是这次的具体需求。
     */
    static String substituteArguments(String body, String args) {
        String text = body == null ? "" : body;
        if (args == null || args.isBlank()) {
            return text;
        }
        if (text.contains("$ARGUMENTS")) {
            return text.replace("$ARGUMENTS", args);
        }
        return text + "\n\n## User Request\n\n" + args;
    }

    /**
     * 按 forkContext 决定子 Agent 继承多少父对话：
     * {@code full} 全量 / {@code recent} 最近 5 条 / 其余（{@code none}）完全隔离。
     */
    static List<Message> buildForkSeed(String mode, List<Message> parent) {
        if (parent == null || parent.isEmpty()) {
            return List.of();
        }
        return switch (mode == null ? "none" : mode) {
            case "full" -> new ArrayList<>(parent);
            case "recent" -> {
                if (parent.size() <= FORK_RECENT_COUNT) {
                    yield new ArrayList<>(parent);
                }
                yield new ArrayList<>(parent.subList(parent.size() - FORK_RECENT_COUNT, parent.size()));
            }
            default -> List.of();
        };
    }
}
