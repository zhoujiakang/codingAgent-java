package com.agentjava.tui;

/**
 * 应用状态机。
 *
 * 当前只有两个状态：
 * <ul>
 *   <li>PROVIDER_SELECT —— 配置里有多份 provider，启动后先让用户选一个；</li>
 *   <li>CHAT —— 正常对话（空闲与流式都用这个状态，靠 streaming 标志区分）。</li>
 * </ul>
 */
public enum AppState {
    PROVIDER_SELECT,
    CHAT
}
