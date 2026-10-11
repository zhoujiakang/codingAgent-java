package com.agentjava.skill;

import com.agentjava.conversation.Message;

import java.util.List;

/**
 * 在 {@link SkillHost} 之上再加一项能力：拉起一个隔离子 Agent。
 *
 * <p>由持有 LLM 客户端与 Agent 构造器的 TUI 层实现，传给
 * {@link SkillExecutor#executeFork}。执行器只定义接口、不依赖具体实现，
 * 这样 {@code skill} 包不需要认识 {@code agent} / {@code llm} 包。
 */
public interface SkillForkHost extends SkillHost {

    /**
     * 起一个独立上下文的子 Agent 跑 SOP，返回它最终说的话。
     *
     * @param body  渲染后的 SOP 正文，作为子 Agent 的第一条用户消息
     * @param seed  按 forkContext 策略从父对话带过来的种子消息
     * @param model Skill 指定的模型；为空表示沿用主循环的模型
     */
    String runSubAgent(String body, List<Message> seed, String model);

    /** 父对话当前消息的快照，供 {@link SkillExecutor} 按策略裁剪。 */
    List<Message> snapshotParentMessages();
}
