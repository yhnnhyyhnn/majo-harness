# 每代理上下文树（里程碑设计）

状态：**设计**。已交付的前置件：每轮 model/systemPrompt 覆盖、`delegate_task`
并行执行（`parallelDelegates`）。本文设计下一里程碑：让每个子代理拥有基于
jcordis 上下文树的**隔离、可释放的代理作用域（agent scope）**。

## 现状

- 每进程一棵根 `Context`（`Context.create()`）；profile 行都挂在这单一上下文上。
- 一轮 turn 归属一个**会话**（持久事件日志），由 `AgentLoopService` 驱动（模型完成 + 工具轮 + 审批/问答座）。
- 今天子代理 = 在同一棵上下文上开**新会话**，复用同一 loop/模型注册表/工具/settings。
  隔离仅：新历史、可选每代理 `model`/`systemPrompt`（runTurn 5 参）、可选工具轮并行。
- Web 每实例串行 turn（一把 `turnLock`）——单用户本地没问题，但限制并发 agent 工作。

## 本里程碑目标

一次子委派作为一个独立 **agent scope** 运行：

1. 作用域配置——模型、提示词、maxSteps、allowedTools、sandbox/审批策略；
2. 作用域服务视图——子代看到**自己的** LLM 门面与工具视图，而非全局可变单例；
3. 生命周期——每次委派启动一个 scope，结束即回滚（Disposable），含注册与未决交互；
4. 并发——多 scope 跑在虚拟线程上；持久事件仍进共享会话存储，转写/投影/标题/web UI 保持连贯。

非目标：每代理独立的磁盘/文件状态与每代理“插件 jar 孤岛”；子代不挂任意新 profile 行。

## 地基：jcordis 上下文树

`io.jcordis.core.context.Context` 已提供所需原语：

- `extend()` / `extend(Map meta)`——继承父隔离与拦截的子上下文；
- `intercept(name, config)`——子上下文按名覆盖服务构造 config；
- `isolate(name[, key])`——每子代独立（或共享）服务键；
- `child(Fiber)`——绑定到某 fiber 的上下文；
- 插件按上下文挂载（`ctx.plugin(...)`）带依赖 epochs；`fiber().disposeAsync()` 拆除子树贡献。

因此**无需新内核**：agent scope = 一个小 `Context.extend()` 子树——用被拦截的
config（重新）挂一个作用域 loop（以后还有作用域工具视图），委派结束即销毁。

## 建议形态

```
AgentSpec {
  model?: string               // 注册模型 id（缺省=根默认）
  systemPrompt?: string
  maxSteps?: number
  allowedTools?: List<String>  // null=继承全部工具
  approvals?: "auto" | "deny"  // 每代理策略（默认根）
  parallel?: boolean           // 目前由父 loop 处理
}
```

子代运行（`SubagentService`）：

```
scope = root.extend(meta:{agent: specId})
        .intercept("agentLoop", loopConfigFor(spec))     // 每代理 loop config
        .intercept("llm", llmConfigFor(spec))            // 默认模型覆盖
scope.plugin(agentLoopPlugin?, spec)
scope.plugin(toolViewPlugin?, spec.allowedTools)         // 作用域 allowlist 视图
... 在 scope 内跑子会话 turn ...
finally scope.fiber().disposeAsync()
```

**持久脊柱保持共享**：会话 store/service、投影、事件总线、技能注册表、凭据一律查根——
子会话写进 UI 已读的同一日志；只有 model/tools/loop/交互**策略视图**做作用域隔离。

现在的 `runTurn` 已把可配部分（模型+提示词）分离；本里程碑把它升级为 spec，
并经由真实子树路由，使配置拦截是“结构化”而非“传参”。

## 交互路由（难点）

审批/ask-user 目前路由到单一前端座（web 每实例 `PendingInteractions`）。并发 scope 下，
子代工具调用要审批而父在等待——UI 必须知道**哪个委派在问**。

设计：每个 agent scope 带 `agentId`；交互请求携带 `(agentId, request)`；SSE 在审批帧
加 `agent` 字段；rail 渲染嵌套卡片（“subagent `web-demo` 请求… ”）。CLI/headless 保持
自动接受，除非 spec `approvals:"deny"`。这是独立切片 M-C2（会触碰
`InteractionService`、WebMain SSE 帧与 approval feature——线类型需重新生成）。

## 里程碑切片

- **M-C1 — 经由上下文子树的 scoped loop。** `AgentSpec` + `delegateSpec(task, spec)`：
  建 `extend()` scope，`intercept` `agentLoop`/`llm` config 后在子会话跑 turn；
  保留 5 参 `runTurn` 作为根上下文快路径。验证：seam 测试两个不同 spec 的子代，
  模型/提示词头各异（按轮已覆盖）且 spec `maxSteps`/`allowedTools` 生效。
- **M-C2 — 按代理交互路由。** `agentId` 贯穿审批/问答；SSE + rail 更新；spec `approvals` 策略。
- **M-C3 — allowlist 与策略视图。** 包装根的工具注册表（spec `allowedTools`），在
  `tools.execute` 处强制；子轮次未知工具给结构化错误。
- **M-C4（可选，日后）— 每子代真插件孤岛。** 若子代需要自己的 profile 行：在子上下文挂
  HarnessBoot-lite 装载器（自有 profile + 独立 classloader）；风险高——先做 epochs/销毁审计。

## 并发与持久化注意

- 兄弟子 turn 已在虚拟线程并发（`parallelDelegates` 的机制）；事件按会话 append 且 store
  同步，但跨会话的投影/广播乱序本就被容忍（watermark 按会话）。
- `delegates` 执行器共享；每个 **scope** 不得重入同一根 turn（depth 护栏保留）。
- Web `turnLock` 只串行**用户** turn；scoped 子代位于单个用户 turn 内、受父工具轮约束——无需再加锁。

## 验证计划

- Seam 测试（离线确定性 mock）：同一父 fan-out 两个 spec 不同的子代；allowedTools 强制；
  maxSteps 上限生效；scope 结束后 dispose 运行（注册回滚；recent-runs 显示 done）。
- Web 冒烟（web-mock + cue mock）：父提示 fan-out → 子审批帧带 agent id → rail 逐子 Allow →
  汇总答案。
- 文档：agent-context + CHANGELOG 更新。

## 待决问题

- `AgentSpec` 只来自模型（工具参数），还是也来自宿主侧策略（profile）？
  答：两者——工具参数是**请求**，宿主策略先**钳制**（model/工具 allowlist、maxSteps）再使用。
- 每子审批 UX 深度：嵌套 rail 卡 vs 折叠计数——在 M-C2 与 web parity 表一起定。
- 执行器规模：现在用虚拟线程；日后可能为防滥用 fan-out 加服务端信号量上限。
