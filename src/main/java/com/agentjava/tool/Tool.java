package com.agentjava.tool;

import java.util.Map;

/**
 * 工具统一接口。
 *
 * <p>所有内置工具都实现这一套契约：名字、描述、分类、JSON Schema 以及执行逻辑。
 * 模型通过 schema 知道有哪些工具、怎么调；框架通过 execute 拿到结果。
 */
public interface Tool {

    /** 工具名，模型在 tool_use 里引用的就是它（如 "ReadFile"）。 */
    String name();

    /** 工具描述，直接作为给模型的 schema 说明，决定了模型会不会、怎么调它。 */
    String description();

    /** 分类：READ / WRITE / COMMAND，约束能不能并发执行。 */
    ToolCategory category();

    /** 该工具的 JSON Schema（input_schema 部分），描述入参。 */
    Map<String, Object> schema();

    /** 实际执行，入参是模型解析好的 Map。 */
    ToolResult execute(Map<String, Object> args);

    /**
     * 工具要不要延迟加载。延迟的工具不出现在初始 tool list 里。
     * 内置工具数量可控，一律不延迟，直接给全量 schema。
     */
    default boolean shouldDefer() {
        return false;
    }

    /**
     * 这一次调用能不能跟别的并发跑，按实际参数判断而不是只看类别。
     * 不覆写就按类别走：只读的可以并发，写和命令类不行。
     */
    default boolean isConcurrencySafe(Map<String, Object> args) {
        return category() == ToolCategory.READ;
    }
}
