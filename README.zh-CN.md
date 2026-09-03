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
| `majo-subprocess` | 子进程能力接缝：`SubprocessProvider` 之上的 `ctx.subprocess`（纯 argv、无 shell）、`subprocess/pre-execute` 策略事件、`run_command` 工具消费者 | `ctx.subprocess`（+ `ctx.tools`） | `subprocess`、`subprocess-tools` |
| `majo-shell` | subprocess 之上的 shell 能力接缝：`ctx.shell` 经 Strategy 选择的 shell 家族、由 `ShellProvider`（对 `ctx.subprocess` 的 Adapter）执行脚本，`shell/pre-execute` 策略事件、`run_shell` 工具消费者 | `ctx.shell`（+ `ctx.tools`、`ctx.subprocess`） | `shell`、`shell-tools` |
| `majo-sandbox` | 沙箱能力接缝：`ctx.sandbox` 在 spawn 前经可换 provider（默认 identity；Linux 装配 bwrap）包裹 argv，`sandbox/pre-confine` 策略事件；shell 消费者用 `confine: true` 应用它 | `ctx.sandbox` | `sandbox` |
| `majo-interaction` | 交互能力接缝：`ctx.interactions` 审批与 ask-user，经由注册 handler（auto/deny/canned；queue 供真人），`tool-approval` 门禁插件挂在 `tools/pre-execute` | `ctx.interactions` | `interactions`、`tool-approval` |
| `majo-skill` | 技能能力接缝：`ctx.skills` 聚合技能 provider（出厂为本地 `SKILL.md` 目录 provider），`list_skills`/`load_skill` 工具消费者 | `ctx.skills`（+ `ctx.tools`） | `skills`、`skill-files`、`skill-tools` |
| `majo-subagent` | 子代理能力接缝：`ctx.subagent` 把任务委派给由 agent loop 驱动的子会话（深度受限），`delegate_task` 工具消费者 | `ctx.subagent`（+ `ctx.tools`） | `subagent`、`subagent-tools` |
| `majo-settings` | 用户设置：`ctx.settings` 键值存储，可选 JSON 文件 provider | `ctx.settings` | `settings` |
| `majo-credentials` | 凭据：`ctx.credentials` 经 provider（出厂 env + `.env` 文件）解析密钥，值永不入日志 | `ctx.credentials` | `credentials` |
| `majo-web-access` | web 访问能力族（dsh `web/`）：可换 search/fetch provider 的 `ctx.web`、匿名 HTTP fetch 后端、静态搜索后端、`web_search`/`web_fetch` 工具 | `ctx.web`（+ `ctx.tools`） | `web`、`web-tools`、`web-fetch-http`、`web-search-static` |
| `majo-title` | 会话标题：`ctx.sessionTitle` 持有唯一标题 provider（出厂 heuristic），由会话日志派生 | `ctx.sessionTitle` | `session-title`、`session-title-heuristic` |
| `majo-boot` | profile→loader 胶水：内置插件注册 + 从 YAML profile 启动 entry 树 | — | — |
| `majo-headless` | 一次性 headless 应用：示例 `calc` 工具、`run` entry、`headless.yml` profile、端到端测试 | — | `calc`、`run` |
| `majo-cli` | 可执行 dsh 风格启动器（shaded jar）：`majo "task" [--profile …]`，打印转写与退出码 | — | — |
| `majo-web` | web profile 应用（dsh 风格聊天 UI）：JDK HTTP 服务器承载已启动树，静态会话/对话页 + JSON turn API | — | （应用） |

文档：[架构](docs/architecture.zh-CN.md) · [architecture (EN)](docs/architecture.md) · [web 对齐](docs/web-parity.zh-CN.md)

## 应用入口（一个 harness、多个入口）

```
能力库(jar):  majo-session / tools / llm / agent-loop / fs / shell / sandbox /
             interaction / skill / subagent / settings / credentials / title / …
组装层:      majo-boot.HarnessBoot（出厂 builtins + profile 解析/launch）
入口:        majo-cli → majo "task"      headless 一次性 runner（转写输出）
             majo-web → java -jar …jar    Web App：JSON/SSE API + React 页面托管
             HeadlessMain                 demo（mvn exec）
前端源码:    web-ui（React/Vite；产物拷入 majo-web 的 resources/static）
```

`majo-web` 是面向浏览器的入口（既作为后端服务在已启动的插件树上运行，又托管编译好的 React 页面）。`majo-cli` 是脚本/一次性任务入口。两者共享同一棵可组合插件树——没有特权内核。

出厂插件已由 `majo-boot.HarnessBoot` 注册为 loader builtins（`session`、`session-projections`、`tools`、`llm`、`llm-mock`、`llm-openai`、`fs`、`fs-tools`、`subprocess`、`subprocess-tools`、`shell`、`shell-tools`、`sandbox`、`interactions`、`tool-approval`、`skills`、`skill-files`、`skill-tools`、`subagent`、`subagent-tools`、`settings`、`credentials`、`session-title`、`session-title-heuristic`、`web`、`web-tools`、`web-fetch-http`、`web-search-static`、`agent-loop`）。profile 选取所需行即可——例如给一次运行加上文件读取能力：

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

## 快速开始（像 CLI 一样启动，对标 dsh）

```bash
mvn -DskipTests install                       # 构建并安装反应堆
java -jar majo-cli/target/majo-cli-0.1.0-SNAPSHOT.jar "1+2"
```

启动器加载内置 `headless` profile（全部出厂插件经 profile 行组合）并运行一次任务——无需 API key，确定性 mock 模型驱动工具轮次：

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

```text
majo --profiles                                # 列出内置 profile
majo --profile ./my-profile.yml "task"        # 任何由内置行组成的 YAML
majo --plugin ext=./ext-plugin.jar "task"     # 挂载外部插件 jar
majo "task"                                   # = --profile headless
```

`run` 行在缺失时自动追加；把模型 provider 换成你自己的端点只需复制 profile 并修改（见下）。`REQUEST_HEADER` 行持久记录每次请求的 model/system prompt/工具名。开发期也可用 `mvn -pl majo-headless exec:java -Dexec.args="1+2"`。

外部插件 jar 遵循 jcordis 契约：SPI 清单 `META-INF/services/io.jcordis.core.registry.Plugin` + 隔离类加载器。用 `--plugin name=./path.jar` 挂载，并在 profile 行里引用 `name`；热替换（`replaceJar`）与卸载走 `HarnessBoot.loader()`。`majo-boot` 里的 `PluginJarTest` 就是构建此类 jar 的现成配方。

`majo chat` 是**多轮对话**的纯文本 TUI：连续输入驱动同一持久会话的连续 turn（历史/工具/投影跨轮生效）；`exit`/Ctrl+D 退出。

## Web UI（React，dsh 风格）

```bash
mvn -DskipTests install
java -jar majo-web/target/majo-web-0.1.0-SNAPSHOT.jar          # http://localhost:8787
```

打开 http://localhost:8787：会话侧栏、用户/工具/assistant 消息气泡、提交器——这是 `web-ui/` 下的 React/Vite 应用，编译产物已提交到 `majo-web/src/main/resources/static`，由 Java 后端直接服务。改动 UI 后用 `bash scripts/build-web-ui.sh` 重建（需 npm）。

随附的 `web.yml` 已指向 kilo 免费层（OpenAI 兼容网关）——**无需 API key**（免费层偶发上游 502，重试即可）。

其他客户端用 JSON API：

```bash
curl -X POST -H 'Content-Type: application/json' -d '{"task":"1+2"}' http://localhost:8787/api/turn
curl http://localhost:8787/api/sessions
curl http://localhost:8787/api/sessions/<id>
```

排障：若页面一片空白，最常见原因是 **8787 被旧实例占用**——新进程会以清晰的 “port … is already in use” 退出，而浏览器仍在访问旧进程。停掉旧的 `java` 进程或换端口（`--port 9000`），再强制刷新（Ctrl+F5）。后端不可达时页面显示可见的 “offline” 横幅，而不是静默空白。

## 自带模型端点

运行 harness 不需要任何 key：确定性 mock 无需网络；`llm-openai` provider 按 OpenAI `chat/completions` 线协议与你选择的任何端点通信（LM Studio、Ollama、vLLM、One-API 类网关，或带你自己 key 的厂商）。替换模型 provider 只需改 profile——把 mock 两行换成 provider 行，并把 `llm.defaultModel` 指向其注册名：

```yaml
- id: session
  name: session
- id: session-projections
  name: session-projections   # agent-loop 必需
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
