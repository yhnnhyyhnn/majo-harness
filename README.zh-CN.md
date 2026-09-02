# majo-harness

<p align="left">
  <a href="README.md">English</a> | 简体中文
</p>

**majo-harness** 是构建在 [jcordis](https://github.com/jcordis/jcordis)（[Cordis](https://github.com/cordiverse/cordis) 的 Java 21 移植）之上的全插件式 agent harness，架构对标 [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)。

产品中的每一项能力——会话日志、工具注册表、模型适配器、甚至 agent loop 本身——都是向共享 `Context` 注册服务与事件的插件。这里没有需要打补丁的特权内核：要扩展 harness，只需在旁边挂载一个插件；所有注册都是可回滚的效果（effect），随插件卸载自动撤销。

## 模块（M1 垂直切片）

| 模块 | 职责 | ctx 键 | 插件行 |
|---|---|---|---|
| `majo-session` | 持久会话日志：append-only `SessionEvent`（内存 / JSONL 文件存储） | `ctx.sessions` | `session` |
| `majo-tools` | 工具接缝：`Tool` / `ToolSpec` / `ToolCall` / `ToolResult` + 受控执行管道（`tools/pre-execute`、`tools/post-execute` waterfall） | `ctx.tools` | `tools` |
| `majo-llm` | 模型消息词汇 + `ChatModel` 适配器接缝 + 注册表 + 确定性 mock provider | `ctx.llm` | `llm`、`llm-mock` |
| `majo-agent-loop` | 默认 turn 驱动器：从会话日志派生模型历史并驱动工具轮次 | `ctx.agentLoop` | `agent-loop` |
| `majo-provider-openai` | OpenAI-compatible 的 `ChatModel` provider——自带端点（LM Studio / Ollama / vLLM / 网关），`apiKey` 可选 | 注册到 `ctx.llm` | `llm-openai` |
| `majo-fs` | 文件系统能力接缝：`FsProvider` 之上的 `ctx.fs`、`fs/*` 策略事件、`read_file` 工具消费者 | `ctx.fs`（+ `ctx.tools`） | `fs`、`fs-tools` |
| `majo-boot` | profile→loader 胶水：内置插件注册 + 从 YAML profile 启动 entry 树 | — | — |
| `majo-headless` | 一次性 headless 应用：示例 `calc` 工具、`run` entry、`headless.yml` profile、端到端测试 | — | `calc`、`run` |

文档：[架构](docs/architecture.zh-CN.md) · [architecture (EN)](docs/architecture.md)

出厂插件已由 `majo-boot.HarnessBoot` 注册为 loader builtins（`session`、`tools`、`llm`、`llm-mock`、`llm-openai`、`fs`、`fs-tools`、`agent-loop`）。profile 选取所需行即可——例如给一次运行加上文件读取能力：

```yaml
- id: fs
  name: fs
- id: fs-tools
  name: fs-tools      # 在 ctx.tools 上注册 read_file 工具
```

## 环境要求

- JDK 21
- Maven 3.x
- 本地 Maven 仓库中存在 `io.jcordis:jcordis-all:1.0.0`

## 快速开始

```bash
mvn test                     # 单元 + 集成测试（mock 模型，无需网络）

mvn -DskipTests install      # 安装模块，供下面的 demo 解析依赖
mvn -pl majo-headless dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "majo-headless/target/classes;$(cat majo-headless/cp.txt)" \
     io.majo.harness.headless.HeadlessMain "1+2"
rm majo-headless/cp.txt
```

启动后打印插件树记录的会话转写：

```text
== session 0e75e45e-... ==
  1 TURN_START {}
  2 USER_MESSAGE content="1+2"
  3 REQUEST_HEADER model="mock" tools=[calc] systemPrompt="You are a small calculator harness. Use the calc tool whenever asked for arithmetic."
  4 ASSISTANT_MESSAGE toolCalls=[{name=calc, arguments={"expression":"1+2"}, toolCallId=...}]
  5 TOOL_RESULT content="3"
  6 REQUEST_HEADER model="mock" tools=[calc] systemPrompt="You are a small calculator harness. Use the calc tool whenever asked for arithmetic."
  7 ASSISTANT_MESSAGE content="calculated: 3"
  8 TURN_END {}
answer: calculated: 3
```

同样的运行完全由 `majo-headless/src/main/resources/headless.yml` 驱动：每一行都是插件 entry，loader 在其注入依赖就绪后按依赖顺序激活——没有任何应用代码把 loop 拼起来。注意其中的 `REQUEST_HEADER` 行：每次模型请求都会把 model、system prompt 与所提供的工具名持久化，因此请求组合始终可从日志重建。

## 自带模型端点

运行 harness 不需要任何 key：确定性 mock 无需网络；`llm-openai` provider 按 OpenAI `chat/completions` 线协议与你选择的任何端点通信（LM Studio、Ollama、vLLM、One-API 类网关，或带你自己 key 的厂商）。替换模型 provider 只需改 profile——把 mock 两行换成 provider 行，并把 `llm.defaultModel` 指向其注册名：

```yaml
- id: llm
  name: llm
  config:
    defaultModel: local        # 下面 provider 的注册名
- id: llm-openai
  name: llm-openai
  config:
    name: local
    model: your-model-id       # 线上发送的模型 id
    baseUrl: http://localhost:1234/v1   # 如 LM Studio / Ollama / 你的网关
    # apiKey: sk-...           # 仅当你的端点需要时
```

自定义 provider 路径由端到端测试离线覆盖：测试对本地 HTTP stub 启动整棵插件树（`customOpenAiCompatibleProviderReplacesTheMock`）。

## License

Apache License 2.0 — 见 [LICENSE](LICENSE)。
