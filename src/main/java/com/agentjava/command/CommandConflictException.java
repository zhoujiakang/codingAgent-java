package com.agentjava.command;

/**
 * 命令注册冲突：名字或别名被两条命令同时占用。
 *
 * <p>这类冲突必须<b>在注册期</b>炸出来，而不是等到用户敲命令的时候才发现——
 * 「{/s} 到底是 /session 还是 /status」这种不确定性一旦流到运行时，就只能靠运气排查。
 * 所以这里让它一启动就失败。
 */
public class CommandConflictException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public CommandConflictException(String message) {
        super(message);
    }
}
