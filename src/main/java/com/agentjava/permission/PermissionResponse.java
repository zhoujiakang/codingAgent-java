package com.agentjava.permission;

/**
 * HITL（人在回路）确认环节里，用户对一次权限请求的三选一回复。
 *
 * <p>语义上值得独立成枚举，它是 Agent Loop 与 UI 之间跨线程回传的契约：
 * <ul>
 *   <li>{@link #ALLOW}：本次放行；</li>
 *   <li>{@link #ALLOW_ALWAYS}：本次放行，并把一条 allow 规则写入本地权限文件，同类操作以后免确认；</li>
 *   <li>{@link #DENY}：本次拒绝，工具以 isError 结果回灌，模型自行调整策略。</li>
 * </ul>
 */
public enum PermissionResponse {
    ALLOW,
    ALLOW_ALWAYS,
    DENY
}
