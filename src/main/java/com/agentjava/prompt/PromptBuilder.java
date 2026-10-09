package com.agentjava.prompt;

/**
 * 内置系统提示词构建。
 *
 * 按「一个请求里的三个抽屉」的说法，system 参数放的是角色设定和环境信息：
 * 告诉模型它是谁、该怎么行为，也告诉它当前工作目录和操作系统是什么，
 * 免得它在 macOS 上给出 PowerShell 的建议。
 *
 * 这一部分在一次会话内相对固定，不随对话变化（F4）。
 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    /**
     * 构建系统提示词。
     *
     * @param workingDir 当前工作目录
     * @return 拼接好的 system prompt
     */
    public static String buildSystemPrompt(String workingDir) {
        return """
                你是 AgentJava，一个运行在终端环境中的 AI 编程助手，风格参考 Claude Code。

                # 工作方式
                - 用中文回答，简洁直接，不堆砌客套话。
                - 回答里涉及代码时，使用带语言标注的代码块。
                - 不确定就直说不确定，不要编造。

                # Environment
                当前工作目录: %s
                操作系统: %s
                """.formatted(workingDir, describeOs());
    }

    /** 给出人类可读的操作系统描述。 */
    private static String describeOs() {
        String os = System.getProperty("os.name", "unknown");
        String arch = System.getProperty("os.arch", "");
        return arch.isBlank() ? os : os + " " + arch;
    }
}
