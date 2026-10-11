package com.agentjava.tool.impl;

import com.agentjava.skill.SkillCatalog;
import com.agentjava.skill.SkillExecutor;
import com.agentjava.skill.SkillForkHost;
import com.agentjava.skill.SkillHost;
import com.agentjava.tool.Tool;
import com.agentjava.tool.ToolCategory;
import com.agentjava.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 按名称激活 Skill，把完整 SOP 带回对话。
 *
 * <p>这是两阶段加载的第二阶段。系统提示词里只列出 Skill 的名字和一句话描述
 * （渐进式披露），模型判断用户意图匹配某个 Skill 后调用本工具，完整 SOP 作为
 * 工具结果进入对话历史——不需要额外的注入通道，工具结果本来就会留在历史里。
 *
 * <p><b>为什么是 READ 类</b>：它只读文件、不改外部状态，跟 ReadFile、Grep 走同一条
 * 通道，因此不需要权限确认，也不会被 Plan Mode 的工具收窄过滤掉。
 */
public class LoadSkillTool implements Tool {

    private static final String DESCRIPTION = """
            Activate a Skill by name and load its full SOP (standard operating procedure).

            Call this when the user's request matches one of the Skills listed in the
            available-skills section, then follow the loaded instructions step by step.

            Usage notes:
            - Pass the Skill name WITHOUT a leading slash (e.g. "commit", not "/commit").
            - Call it before starting the work, not after.
            - 加载后按 SOP 里的 Workflow 执行；SOP 里引用的附带文件用 ReadFile / Bash 按需读取。""";

    private SkillCatalog catalog;

    /** inline 模式的宿主：负责把激活的 SOP 钉进环境上下文，供压缩恢复。 */
    private SkillHost host;

    /**
     * fork 模式的宿主。
     * 为 null 时（宿主没接子 Agent 运行时）fork 声明降级成 inline——工具仍然可用，
     * 只是失去了隔离效果。宁可降级也不要报错，模型还能继续干活。
     */
    private SkillForkHost forkHost;

    public void setCatalog(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    public void setHost(SkillHost host) {
        this.host = host;
    }

    public void setForkHost(SkillForkHost forkHost) {
        this.forkHost = forkHost;
    }

    @Override
    public String name() {
        return "LoadSkill";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of(
                                        "type", "string",
                                        "description",
                                        "The Skill name to activate (e.g. \"commit\", \"backend-interview\")."
                                )
                        ),
                        "required", List.of("name")
                )
        );
    }

    /**
     * 四道校验依次拦下「参数为空 / catalog 未接入 / Skill 不存在 / 正文为空」，
     * 每种给不同的错误文案——模型看到错误后要能自己调整，含糊其辞只会让它重试同样的参数。
     */
    @Override
    public ToolResult execute(Map<String, Object> args) {
        String name = String.valueOf(args == null ? null : args.getOrDefault("name", "")).trim();
        if (name == null || name.isEmpty()) {
            return ToolResult.error("name is required");
        }
        if (catalog == null) {
            return ToolResult.error("LoadSkill not initialized (catalog is null)");
        }

        var skill = catalog.getFull(name);
        if (skill.isEmpty()) {
            return ToolResult.error("unknown skill: " + name);
        }

        String body = skill.get().promptBody();
        if (body == null || body.isEmpty()) {
            return ToolResult.error("skill \"" + name + "\" has empty body — cannot activate");
        }

        // fork 模式：正文不进主对话，丢给隔离子 Agent 执行，只把结论带回来。
        // 模型自己调 LoadSkill 和用户敲斜杠命令因此遵循同一套 mode 语义。
        if (skill.get().meta().isFork() && forkHost != null) {
            try {
                return ToolResult.success(SkillExecutor.executeFork(skill.get(), "", forkHost));
            } catch (Exception e) {
                return ToolResult.error("skill \"" + name + "\" fork execution failed: " + e.getMessage());
            }
        }

        if (host != null) {
            host.activateSkill(name, body);
            host.recordSkillInvocation(name, body);
        }
        return ToolResult.success("# Skill: " + name + "\n\n" + body);
    }
}
