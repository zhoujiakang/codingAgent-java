package com.agentjava.command;

import com.agentjava.memory.MemoryFile;
import com.agentjava.permission.PermissionMode;
import com.agentjava.session.SessionInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 命令上下文：命令执行时能拿到的全部依赖，打包成一个「背包」。
 *
 * <p>依赖一律用 {@link Supplier} / {@link IntSupplier} / {@link Runnable} 包装，而不是直接
 * 传引用。两个好处：
 * <ol>
 *   <li><b>惰性求值</b>：handler 用不上就不 {@code get()}，取值操作不会发生。
 *       比如 {@code /memory} 不需要工具数量，那次遍历就不会白跑；</li>
 *   <li><b>延迟绑定</b>：构造 ctx 时 provider 可能还没激活（权限模式、工具数、
 *       上下文窗口都可能在命令执行前发生变化），函数式取值保证读到的永远是当下。</li>
 * </ol>
 *
 * <p>刻意<b>不放</b>的东西：{@code ConversationManager}、{@code ToolRegistry}、
 * {@code LlmClient} 这类重型业务对象。它们一旦进来，ctx 就退化成一个到处传递的
 * god object，命令层也跟着依赖半个工程。真正需要这些资源的命令（如 {@code /compact}）
 * 按 {@link CommandType#LOCAL_UI} 处理，由 UI 层按名字兜底。
 */
public record CommandContext(String args,
                             UIController ui,
                             String workDir,
                             String model,
                             String version,
                             String sessionId,
                             Supplier<PermissionMode> permissionMode,
                             IntSupplier toolCount,
                             IntSupplier contextWindow,
                             Supplier<List<MemoryFile>> userMemories,
                             Supplier<List<MemoryFile>> projectMemories,
                             Runnable clearMemories,
                             Supplier<List<SessionInfo>> sessions) {

    /** 用户级记忆条数（Suppliers 返回 null 时按 0 处理，命令不该因为空目录崩掉）。 */
    public int userMemoryCount() {
        List<MemoryFile> list = userMemories == null ? null : userMemories.get();
        return list == null ? 0 : list.size();
    }

    /** 项目级记忆条数。 */
    public int projectMemoryCount() {
        List<MemoryFile> list = projectMemories == null ? null : projectMemories.get();
        return list == null ? 0 : list.size();
    }

    /** 全部记忆文件：用户级在前、项目级在后。 */
    public List<MemoryFile> allMemories() {
        List<MemoryFile> user = userMemories == null ? List.of() : orEmpty(userMemories.get());
        List<MemoryFile> project = projectMemories == null ? List.of() : orEmpty(projectMemories.get());
        var out = new ArrayList<MemoryFile>(user.size() + project.size());
        out.addAll(user);
        out.addAll(project);
        return out;
    }

    /** 历史会话列表，NullPointerException-free。 */
    public List<SessionInfo> allSessions() {
        List<SessionInfo> list = sessions == null ? null : sessions.get();
        return list == null ? List.of() : list;
    }

    /** 当前权限模式；取不到时退回 DEFAULT。 */
    public PermissionMode currentPermissionMode() {
        PermissionMode mode = permissionMode == null ? null : permissionMode.get();
        return mode == null ? PermissionMode.DEFAULT : mode;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
