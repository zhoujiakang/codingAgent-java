package com.agentjava.skill;

/**
 * 主 Agent 对 Skill 包暴露的能力切片。
 *
 * <p>做成接口是为了断掉循环依赖：{@code skill} 包要在执行 Skill 时通知宿主
 * 「这个 Skill 被激活了」，但宿主（TUI 层）又依赖 {@code skill} 包。两边互相
 * import 会让包结构烂掉，所以让宿主实现这个接口传进来，执行器只认接口。
 *
 * <p>只放两个方法，够用就停：Skill 执行器不该拿到 Agent 的方向盘。
 */
public interface SkillHost {

    /**
     * 记一笔「这个 Skill 被激活了」。
     * 用于 {@code /skill} 查看当前激活列表，以及压缩后的上下文恢复。
     */
    void activateSkill(String name, String body);

    /**
     * 记录这次调用，供 Layer 2 压缩使用：对话被摘要清空后，能从记录里把 SOP
     * 重新贴回上下文，避免「压完一次缩，模型就忘了自己在按哪份 SOP 干活」。
     *
     * <p>默认空实现：不跟踪恢复状态的宿主就不用管它。
     */
    default void recordSkillInvocation(String name, String body) {
    }
}
