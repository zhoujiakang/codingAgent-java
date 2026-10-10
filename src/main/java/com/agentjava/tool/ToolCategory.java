package com.agentjava.tool;

/**
 * 工具分类。
 *
 * <p>READ 类工具只读不改外部状态，可以并发执行；
 * WRITE（写文件）和 COMMAND（执行命令）会改状态，必须串行。
 * 这一分类是后面权限与调度策略的依据。
 */
public enum ToolCategory {
    READ, WRITE, COMMAND
}
