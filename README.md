# AgentJava

一个用 Java 编写的命令行 coding agent。


## 当前能力

- 对接 LLM 并**流式**接收回答（SSE），边出字边渲染。
- 多 provider 支持：Anthropic（Messages API）、OpenAI（Chat Completions API）、以及 OpenAI 兼容服务（DeepSeek / Kimi / GLM 等，走 `openai-compat`）。
- **多轮对话**：历史消息会随每一轮累积并回传给模型。
- 终端 TUI：等待时显示 spinner + 实时计时，回答结束后整段用 Markdown 定型渲染（代码块、列表等）。
- 配置系统：支持项目级 / 全局两级 `config.yaml`。


## 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 | Java 21（Gradle toolchain） |
| 构建 | Gradle 8.13（Kotlin DSL），`shadow` 插件打 fat jar |
| 终端 I/O | JLine 3.28（raw mode、按键读取、光标控制） |
| Markdown 渲染 | Mordant 3.0.2 |
| LLM SDK | anthropic-java 2.34、openai-java 4.37 |
| 配置 / JSON | SnakeYAML 2.2、Jackson 2.21.3 |
| 测试 | JUnit 5（Jupiter）5.11.4 |

## 目录结构

```
agent_java/
├── bin/agentjava              # 启动脚本（定位 jar，不切换工作目录）
├── build.gradle.kts          # 构建配置
├── settings.gradle.kts
├── .agentjava/
│   └── config.yaml.example    # 配置示例（真实 config.yaml 已被 .gitignore 忽略）
├── gradle/                    # Gradle wrapper
└── src/
    ├── main/java/com/agentjava/
    │   ├── AgentJava.java      # 入口
    │   ├── config/             # 配置加载（项目级→全局两级解析）
    │   ├── prompt/             # system prompt 构造
    │   ├── llm/                # LLM 客户端 + 流式 StreamEvent
    │   ├── conversation/       # 消息模型与多轮管理
    │   └── tui/                # 应用层 TUI（AgentModel、Markdown 渲染、样式）
    │       └── tea/            # TUI 框架（Elm 架构：Model/Update/View/Command）
    └── test/java/com/agentjava/  # 单元测试
```

## 构建

需要本地有 Java 21（或由 Gradle toolchain 自动拉取）。产物是胖 jar：

```bash
./gradlew shadowJar
# 产物：build/libs/agentjava.jar
```

## 配置

配置解析顺序：**项目级** `agent_java/.agentjava/config.yaml` → **全局** `~/.agentjava/config.yaml`。
找到第一个即使用；两者都没有时启动会给出明确报错并指出候选路径。

1. 复制示例文件并按需填写（真实密钥不要提交，已在 `.gitignore` 中忽略）：

   ```bash
   cp .agentjava/config.yaml.example .agentjava/config.yaml
   # 或放到全局：cp .agentjava/config.yaml.example ~/.agentjava/config.yaml
   ```

2. 以 DeepSeek 为例（`openai-compat`，**`base_url` 必须带 `/v1`**）：

   ```yaml
   providers:
     - name: deepseek
       protocol: openai-compat
       base_url: https://api.deepseek.com/v1
       api_key: "sk-你的key"
       model: deepseek-chat
   ```

- 只配**一个** provider：启动直接进入对话。
- 配了**多个**：启动后用 `↑`/`↓` 选择，回车确认（选定后本次会话不再切换）。

## 运行

启动脚本会定位到 `build/libs/agentjava.jar`，**刻意不切换工作目录**——你当前所在的目录会作为 agent 的工作目录写进 system prompt。

```bash
# 方式一：直接用 jar
java -jar build/libs/agentjava.jar

# 方式二：用 bin 下的启动脚本（任意目录均可，只要 jar 已构建）
./bin/agentjava

# 一次性问答（答完自动退出）
./bin/agentjava -p "帮我看下当前目录有哪些文件"
```

- 交互模式输入后按 `Enter` 发送；`Alt+Enter` / `Ctrl+J` 换行；`Ctrl+C` 退出。
- 输入 `/exit` 或按 `Ctrl+C` 可随时退出，终端状态会正常恢复。
- 进入别的目录后再运行，agent 就会在该目录下工作。

## 测试

```bash
./gradlew test
```

## 说明

- 仓库只包含 `agent_java` 工程本身。
