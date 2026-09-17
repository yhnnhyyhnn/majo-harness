# Roadmap 0.4（加固 + MCP）

majo-harness 是 deepseek-harness（"dsh"）架构的 Java 21 移植版。0.3 路线图
随 v0.2.0 收官（四期全部交付）；本周期先加固既有面，再补上最缺的一块
生态面：MCP 客户端。顺序保证每期可独立交付；状态记录在 CHANGELOG
（`## [Unreleased]`）。

## Phase 1 — 持久性与测试加固（无新增用户可见面）

越晚做成本越高的债务；三项互相独立。

- **会话格式代际化**（参照 dsh
  `packages/session/session-persistence-jsonl`）：持久文件命名
  `session.v1.jsonl`；已提交代际不可变；header-only stat/list（目录枚举
  不再解析整个事件日志）；一步式迁移链（`v(n)` → `v(n+1)`），未知版本
  fail-loud。内存 store 不动。验收：既有落盘数据在改名后透明可开，
  export/import 与回放不受影响，遗留的无版本名 `sessions.jsonl` 一步迁移。
- **LLM 故障注入 mock server**（参照 dsh
  `packages/test-support/llm-mock-server`）：可脚本化的本地 server（及直连
  `ChatModel` 包装器），注入流中断连、429/5xx 响应、畸形 SSE chunk。
  验收：loop/provider 测试钉死故障契约——被杀的流让回合 fail-loud，会话
  日志保持一致（无半提交回合），重试行为是显式契约而非偶然。
- **工具结果裁剪**（参照 dsh `packages/compaction`）：超长工具结果在
  *派生*历史中被裁剪（优先裁更早的轮次），代之以可见的
  `[pruned N chars]` 占位；持久日志不动（"model-visible means logged"
  依旧成立——占位符即模型所见）。
  验收：`MessageDeriver` 测试钉死占位行为；不触发整段压缩即可降低上下文
  压力。

机动项（仅在顺手时做）：composer 字号调节；图片卸载继续推迟。

## Phase 2 — MCP 客户端（主轴）

dsh 对齐面里现在唯一值得建的大型生态面：MCP 客户端让 majo 直接消费现成
的 MCP server 生态，而不必每个能力都手写 Java 插件。走标准模块模式
（Service + Plugin + `ToolRegistry` 桥接）。

- **传输**：先做 stdio（按 profile 行拉起 MCP server 进程）；HTTP/SSE
  传输仅在出现具体需求时加。
- **注册**：profile 行按名声明 server（command、args、env——env 只写
  变量名， riding credentials-by-name）；boot 与插件挂载时连接；单点
  失败 fail-loud 但不拖垮整体启动。
- **工具桥**：`tools/list` 结果以命名空间工具（`mcp__<server>__<tool>`）
  桥接进 `ToolRegistry`，JSON-schema 参数做映射；自动出现在 `/api/tools`
  与生成的工具目录里。
- **调用与安全**：`tools/call` 走既有审批接缝（会话策略 `ask|never|auto`
  生效，fail-closed）并带调用超时；结果作为普通工具结果进入转写。
- **Resources/prompts**：推迟，除非顺带就能落。
- 验收：测试用 MCP server（filesystem 或 echo）在 CI 内挂载；其工具可见、
  可调用、经审批审计，并出现在工具目录 gen+verify 门禁中。

## 0.4 明确不做

Workflow 编排（DSL 应从真实使用中长出来）、agent-team（上游也是实验性）、
终端 PTY、ACP/SDK 远程面、桌面端。SQLite FTS 继续作为按会话搜索缓存
之后的文档化升级选项。
