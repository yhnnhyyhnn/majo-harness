# Roadmap 0.3（参照 dsh 的定制迭代）

majo-harness 是 deepseek-harness（"dsh"）架构的 Java 21 移植版。本路线图以
参考项目为准绳排布后续迭代：dsh 做了什么、majo 还缺什么。依据 2026-09 对
`D:\code\git\deepseek-harness`（架构文档 + `packages/`）的审计结果编写。
状态记录在 CHANGELOG（`## [Unreleased]`）；**Phase 0 与 Phase 1 已交付**，
下一步是 Phase 2。

阶段顺序（已确认）：先工程地基，再移植 dsh 架构机制，然后补齐功能面，
最后生态加固。每期可独立交付。

## Phase 0 — 工程地基（进行中）

让后续每一期都更便宜的债务清理；对外 API 契约零变化。

- **拆分 WebMain**：1715 行的 God class 拆为组装层（`WebMain`）+ 路由表
  （`Router`）+ 按域划分的 `handler/` 包 + 独立的
  `Metrics`/`PendingInteractions`/`Http`/`WebContext`/`Version`。
  `OpenApiDriftTest` + `ConcurrencySoakTest` 双守卫（全绿）。
- **日志纪律**：服务代码用 slf4j（+ `slf4j-simple` runtime）替换
  `System.out`/`printStackTrace`；`scripts/verify-no-stdout.sh` 作回归门禁
  （产品输出通道豁免——CLI、transcript 打印器、`WebTypesGenerator`）。
- **安全细节**：常量时间 token 比较（`MessageDigest.isEqual`）；`?token=`
  SSE 通道的存在原因与风险写入 README 安全模型章节。
- **版本号来自构建**：`/api/info` + `/api/health` 读取 Maven 过滤注入的
  `majo/version.properties`；代码中不再有硬编码副本。
- **前端拆分**：`App.tsx`（783 行）瘦身为壳层；
  `components/SessionSidebar`（搜索 + 管理 + 列表——搜索命中恢复需刷新
  归档视图，故为一体）、`components/PluginFrame`（iframe + postMessage 桥）、
  `components/Composer`（斜杠命令仲裁）。`usePollingSection` 消除
  Plugins/Subagents/Skills 三处轮询样板。ESLint 9 flat config 零告警。
- **文档还债**：web-parity 的 markdown 表格行修正为 ✅（GFM 表格渲染器
  与单测早已交付）。

## Phase 1 — 架构对齐（移植 dsh 核心机制）

dsh 质量门槛背后的机制，映射到 jcordis 上。

- **turn/step + 双 inbox**（参照 `packages/core/agent-loop/src/agent.ts`）：
  turn = 0..n 个 step，仍是持久提交边界；新增 `send/followup/steer/inject`
  入口与 wake 闩锁，让排队工作收敛而不是与进行中的回合竞争。
  `majo-agent-loop` 保留按会话串行；inbox 成为唯一入口。
- **审批作为持久审计事件**（参照 `packages/interaction/user-approval`）：
  ask/decision 事件对以 open turn 包围写入会话日志（崩溃一致），并增加
  会话级策略（`ask | never`，fail-closed），替代目前仅有内存 pending 队列
  + 超时拒绝的做法。
- **"model-visible means logged" 不变量**：漂移测试仅从会话日志重建模型
  请求，断言与 `REQUEST_HEADER` 记录一致（参照 `docs/architecture.md`
  §invariants）。
- **LLM 录制回放测试基建**（参照 `packages/test-support/llm-replay`）：
  录制型 `ChatModel` 包装器把 provider 流写入 fixture，测试离线回放；
  附带脚本化故障 mock 服务器（断连、429/5xx、畸形 chunk，对应
  `llm-mock-server`）。
- **会话格式代际化**（参照 `packages/session/session-persistence-jsonl`）：
  持久文件命名 `session.v1.jsonl`，已提交代际不可变，header-only
  stat/list，一步式迁移链。
- **Gate 聚合脚本**（参照 `scripts/run-gates.ts`）：单一入口
  （`scripts/check.sh`）本地与 CI 跑同一组检查——Maven verify、vitest、
  lint、`verify-no-stdout.sh`、类型漂移。

## Phase 2 — 功能补齐（四个批次，按用户选定顺序）

新能力模块一律走标准模式（Service + Plugin + tool 消费者 + web API +
UI 区块）。dsh 参照实现即行为规格。

- **批次 A · 计划与待办**（参照 `packages/plan/plan-mode`、`packages/todo`）：
  `/plan` 命令 + `exit_plan_mode` 工具走交互接缝（评审卡：批准计划 /
  带反馈继续规划）；plan 投影状态跨 resume 存活。`todo_write`
  整表替换式清单持久化进会话日志，会话区渲染 TodoPanel。
  模块：`majo-plan`、`majo-todo`。
- **批次 B · 后台任务与定时**（参照 `packages/jobs`、`packages/schedule`）：
  `ctx.jobs` 接缝（稳定 `<kind>-N` id、按 owner 并发上限）+ shell 工具
  `run_in_background` + `job_output/job_list/job_kill` 工具 + 头部任务
  列表；完成以 notice 注入（busy → 下轮送达，idle → 唤醒）。Schedule：
  `schedule_create/list/delete`，支持 `after_seconds` / 绝对 `at` /
  `every_seconds ≥ 300`，记录进会话日志跨重启存活，投递给活跃根 agent
  作为 follow-up 回合；头部目录 popover。模块：`majo-jobs`、`majo-schedule`。
- **批次 C · 上下文管理**（参照 `packages/compaction`、
  `packages/llm/token-meter`）：压力触发自动压缩 + `/compact`（人类命令，
  不耗模型轮）、工具结果裁剪、图片卸载（stretch）；token 计量喂给
  Context Meter 面板。模块：`majo-compaction`。
- **批次 D · UI 体验**（参照 `packages/client/ui-trajectory`、`ui-theme`）：
  Trajectory 轨迹视图——turn/step 分组事件台账 + 时长 + Chat/Trajectory
  视图环 + 台账内搜索；主题切换（CSS 变量实现 light/dark/system）+
  输入区字号。

## Phase 3 — 生态深化

- **工具目录生成化**（参照 `docs/tool-catalog.md` + verify 脚本）：
  启动/测试期从 `ToolRegistry.specs()` 生成工具目录，文档漂移即 CI 失败
  （"gen + verify" 成对模式）。
- **凭证按名引用**（参照 `packages/credentials`）：profile 只引用环境变量
  名，永不落值；web 层只能看到"已设置 / 未设置 / 来源"。
- **搜索索引化**：用按会话倒排索引（或 SQLite FTS）替换 `/api/search`
  的 O(N) 全量扫描，端点不变。
- **发布流水线**（参照 `scripts/release/*`）：lockstep 版本提升、按依赖
  拓扑排序发布、打包产物校验。

## 0.3 明确不做

Workflow 编排、agent-team、MCP 客户端、终端 PTY、ACP/SDK 远程面、桌面端
——dsh 都有，但 majo 的单用户 web/CLI 定位应先消化 Phase 0–2，0.3 之后
再评估。
