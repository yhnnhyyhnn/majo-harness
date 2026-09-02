# majo-harness 架构

<a href="architecture.md">English</a> | 中文

改动任何 `majo-*` 模块前请先读本文。假设你了解 Cordis/jcordis 的术语（`Context`、`Fiber`、effect、waterfall、loader entry）；以 jcordis 的 README 与示例为准。

## majo-harness 是什么

majo-harness 是一个**全插件式 agent harness**：每一项产品能力都是向共享 jcordis `Context` 树贡献服务、事件与可回滚效果（effect）的插件。它在 Java/jcordis 上镜像 deepseek-harness（dsh）基于 cordis 的拆解：

| dsh（TypeScript/cordis） | jcordis 机制 | majo-harness（Java） |
|---|---|---|
| profile/preset YAML 组合插件树 | loader Entry 树 + 配置解析 + 依赖 epoch | `majo-boot.HarnessBoot` 从 profile YAML 启动 entry 树 |
| 包 `core/session` — append-only `SessionEvent` 日志 | `Service` + fiber effect | `majo-session`（`ctx.sessions`） |
| 包 `core/tools` — 注册表 + 受控执行 | events / waterfall | `majo-tools`（`ctx.tools`） |
| 包 `llm/llm` — 消息词汇 + 适配器接缝 | `Service` 注册表 | `majo-llm`（`ctx.llm`）+ provider 插件（当前为 mock，后续 `majo-provider-*`） |
| 包 `core/agent-loop` — 默认驱动器 | 声明注入的插件 | `majo-agent-loop`（`ctx.agentLoop`） |
| 包 `boot/app-boot` — profile 胶水 | loader builtins 注册 | `majo-boot` |
| 内置 profile（`web`、`headless`、…） | profile 文件 + 应用插件 | `majo-headless` + `headless.yml` |

M1 **刻意不设特权核心模块**：与 dsh 在 `packages/core/` 下彼此独立的包一样，每个能力在自己的模块里同时拥有接口、实现与插件；消费者（agent loop、boot）只通过服务接缝依赖这些模块。若未来确实需要一个中立的"API 主轴"模块（例如跨模块共享的 typed 事件字典），同样以抽模块的方式引入。

## 仓库布局

```
majo-session/     session log：SessionEventType / SessionEvent / SessionStore
                  （InMemory + JSONL FileSessionStore）/ SessionService / SessionPlugin
majo-tools/       ToolCall / ToolResult / ToolSpec / Tool / ToolRegistry / ToolEvents / ToolsPlugin
majo-llm/         ChatRole / ChatMessage / ChatRequest / ChatResponse / ChatModel / LLMService
                  / LLMServicePlugin / MockChatModel / MockLLMPlugin
majo-agent-loop/  MessageDeriver / AgentLoopService / AgentLoopPlugin
majo-boot/        HarnessBoot（builtins 注册、profile 解析、launch）
majo-headless/    HeadlessMain / CalculatorTool / CalculatorToolPlugin / RunnerPlugin / headless.yml
docs/             本文档（中英双语）
```

## ctx 键与事件字典

| ctx 键 | 由插件提供 | 类型 | 职责 |
|---|---|---|---|
| `sessions` | `session` | `SessionService` | 创建/追加/读取持久日志；广播 `session/event` |
| `tools` | `tools` | `ToolRegistry` | 注册工具（可回滚）、经管道执行 |
| `llm` | `llm` | `LLMService` | 模型注册表 + `complete()`；触发 `llm/request`、`llm/response` |
| `agentLoop` | `agent-loop` | `AgentLoopService` | `runTurn(sessionId, userText)` |

| 事件 | 类型 | 参数 | 语义 |
|---|---|---|---|
| `session/event` | emit | `(SessionEvent)` | 每次持久追加，供实时观察者 |
| `llm/request` | emit | `(ChatRequest, String model)` | 一次补全之前 |
| `llm/response` | emit | `(ChatRequest, ChatResponse, String model)` | 一次补全之后 |
| `tools/pre-execute` | waterfall | `(ToolCall, Tool)` | 改写或拒绝调用；不调用 `next()` 即拒绝 |
| `tools/post-execute` | waterfall | `(ToolCall, ToolResult)` | 观察或变换结果 |

waterfall 监听器必须调用 `next()` 让权（jcordis 约定）。事件即扩展点：策略、审批、遥测、护栏插件都在这里挂载，无需 import agent loop。

## 组合与生命周期

运行中的 harness 是从 YAML 行启动的 loader entry 树。每一行指定一个已注册为 builtin 的插件（出厂插件见 `HarnessBoot`，应用插件用 `.register(name, plugin)` 追加）。行可带 `config`；entry/插件 `inject` 声明列出服务依赖。

激活遵循 jcordis 依赖 epoch：

1. **PENDING** — loader 已创建 entry fiber，但某个声明的注入还没有被 ACTIVE fiber 提供。
2. 每次 `Service` 构造（插件体内 `new SessionService(ctx, …)`）即提供服务；loader 通知依赖方使其加载——按 profile 行序是确定性的。
3. **ACTIVE** — 插件体已执行、注册已生效。
4. 提供者消失（删除 entry、fiber 销毁）：依赖方**回滚效果并回到 PENDING**；提供者回归后自动重激活。`removingAProviderDeactivatesItsDependentsAndReactivationReturns` 验证了级联与回归两条路径。

两条效果规则保证插件树可安全变更：

- **注册可回滚。** 插件体从 `apply` 返回 disposables（如 `return tools.register(tool)`），fiber 收集并在卸载时逆序回滚。工具/模型注册因此绝不会比其 provider 活得更久——不要在服务的 `ctx` 里做注册，那会把效果绑到注册表所属的 fiber 而非调用方 fiber 上。
- **监听随插件回滚。** `ctx.on(...)` 本身是 fiber 效果，插件重载不会泄漏观察者。

错误配置大声失败：未知 profile 行名在 `HarnessBoot.launch` 创建任何 entry 前即被拒绝；工具/模型/服务重复注册抛异常；turn 超过 `maxSteps` 抛异常而不是死循环。

## 一个 turn

`runTurn(sessionId, userText)`（`AgentLoopService`）即一个 turn。通过 `ctx.sessions` 追加的持久事件序列：

```text
TURN_START
USER_MESSAGE content=userText
  step 1..n（每步一次模型请求）：
ASSISTANT_MESSAGE content? toolCalls?       # 原样记录
TOOL_RESULT    toolCallId, name, ok, content  # 每次调用一条
  ...直到模型不带工具调用作答...
ASSISTANT_MESSAGE content=final
TURN_END
```

每步 loop 从日志派生模型历史（`MessageDeriver`），前置 system 消息，提供全部已注册工具 spec，经 `ctx.llm` 完成请求，**按模型可见的原样**记录 assistant 轮次，再经 `ctx.tools` 执行其请求的调用（走 `tools/pre-execute` → tool → `tools/post-execute`）。

## 模型可见 ⟺ 被记录

任何到达模型请求的内容都必须能从会话日志重建。M1 的 loop 在形态上落实了该约束：每个请求的历史完全由事件派生，每条 assistant 轮次在其工具调用执行前先落日志。两处 M1 边界是明示的取舍，而不是可藏在背后的例外：

- system 消息由配置派生（存在于 profile），不是 session 事件；持久的 `request/header` 类事件随 typed projection 里程碑引入。
- `SessionEvent.fields` 是开放 JSON map；对日志的 typed projection（镜像 dsh 的 merge-extensible `SessionEventMap`）是下一个里程碑，应在任何转写 UI 或回放工具依赖 schema 之前落地。

## 约定

- 服务是 `io.jcordis.core.service.Service` 子类，在插件体内提供；`Service` 构造时在自己的 fiber 上注册，并兼作按隔离域过滤的 `EventFilter`。
- 插件类实现 `io.jcordis.core.registry.Plugin`，并以**实例**注册（`loader.builtin(name, new XxxPlugin())`）。`Plugin.constructor(Class)` 面向类插件/`Initializable` 模式，不会执行 `Plugin.apply`——不要用它挂这些插件体。
- 在插件上声明服务依赖（`inject()`），让就绪与级联交给 loader，而不是手工排序；profile 行保持精简。
- 注册的 builtin 名以插件类上的 `NAME` 常量为准；ctx 键与事件名以服务/事件持有者上的常量为准——一个事实只有一个家。
- 大声失败：在最早可解析处抛异常；绝不静默跳过缺失引用。
- 测试描述行为。M1 每个接缝都有聚焦测试，外加一个端到端测试：启动出厂 profile，断言持久事件序列、模型可见请求历史，以及删除/回归级联。

## 路线图

- **M1（已完成）** — 全插件垂直切片：profile 驱动启动、会话日志、工具管道、mock LLM、agent loop、删除级联。全程无网络。
- **M2** — `ChatModel` 背后接真实 DeepSeek provider（`majo-provider-deepseek`）、基于工具 schema 的 prompt 组装、默认启用文件会话存储、typed 会话投影（`request/header` 事件）。
- **M3** — 逐一镜像 dsh 的能力接缝：fs/shell/subprocess、沙箱与审批策略、skills、subagent、交互（ask-user/approval）、settings/credentials、会话标题；每项都是 Service Definition + Provider + Consumer 三件套加 profile 行与 e2e。
- **M4** — 打包与分发：经 jcordis loader SPI/HMR 加载插件 jar、在 `HarnessBoot` 之上做 profile/bundle 分层与 patch、CLI 与 SDK 面。
