package com.agentjava.teams;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * teammate 名称到 agentId 的全局注册表（单例）。
 *
 * <p>SendMessage 按名字寻址时用它把「收件人名字」解析成真实的投递标识；
 * spawn 时登记，deleteTeam 时注销。既支持用名字查，也支持直接传 agentId。
 */
public final class AgentNameRegistry {

    private static final AgentNameRegistry INSTANCE = new AgentNameRegistry();

    private final Map<String, String> nameToId = new LinkedHashMap<>();

    private AgentNameRegistry() {}

    public static AgentNameRegistry getInstance() { return INSTANCE; }

    public synchronized void register(String name, String agentId) {
        nameToId.put(name, agentId);
    }

    public synchronized String resolve(String nameOrId) {
        if (nameToId.containsKey(nameOrId)) return nameToId.get(nameOrId);
        if (nameToId.containsValue(nameOrId)) return nameOrId;
        return null;
    }

    public synchronized void unregister(String name) {
        nameToId.remove(name);
    }

    /** 清空全部映射，主要用于测试隔离。 */
    public synchronized void clear() {
        nameToId.clear();
    }
}
