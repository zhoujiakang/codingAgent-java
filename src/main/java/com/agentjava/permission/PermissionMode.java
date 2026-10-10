package com.agentjava.permission;

import com.agentjava.tool.ToolCategory;

/**
 * 权限模式。
 *
 * <p>用一层「整体信任等级」覆盖日常场景，避免一条条写规则。矩阵用嵌套 switch
 * 表达式表达（而非查表），行为跟着枚举数据走；编译器对枚举 switch 做穷举检查，
 * 漏掉一个分支或新增一个 ToolCategory 忘了处理都会直接编译报错。
 *
 * <p>四种模式覆盖从完全不信任到完全信任的光谱：
 * <ul>
 *   <li>{@link #DEFAULT}：只读放行，写/命令需确认；</li>
 *   <li>{@link #ACCEPT_EDITS}：写文件也放行，仅命令需确认；</li>
 *   <li>{@link #PLAN}：矩阵等同 DEFAULT，真正的只读约束靠 Plan Mode 的 System Prompt 提醒；</li>
 *   <li>{@link #BYPASS}：跳过规则/模式/HITL，仅黑名单与敏感路径禁写仍生效（危险，仅受控环境）。</li>
 * </ul>
 */
public enum PermissionMode {

    DEFAULT,
    ACCEPT_EDITS,
    PLAN,
    BYPASS;

    /** 单层判定结果：放行 / 拒绝 / 需用户确认。 */
    public enum Decision {
        ALLOW, DENY, ASK
    }

    /** 按工具分类给出该模式下的默认决策。 */
    public Decision decide(ToolCategory category) {
        return switch (this) {
            case DEFAULT -> switch (category) {
                case READ -> Decision.ALLOW;
                case WRITE, COMMAND -> Decision.ASK;
            };
            case ACCEPT_EDITS -> switch (category) {
                case READ, WRITE -> Decision.ALLOW;
                case COMMAND -> Decision.ASK;
            };
            // Plan Mode 在矩阵层面与 Default 完全一致；只读约束由 Layer 0 + System Prompt 实现
            case PLAN -> DEFAULT.decide(category);
            // BYPASS 模式在 checker 里走专门短路逻辑，这里仅为完整枚举提供定义
            case BYPASS -> Decision.ALLOW;
        };
    }
}
