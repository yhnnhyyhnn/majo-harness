# Roadmap 0.5（MCP 传输 + 规模化）— Phase 1 已交付；context 注入族已提前采纳（2026-09-18 审计后）

0.4 交付了持久性加固（会话文件代际、LLM 故障注入、工具结果裁剪）与 stdio
MCP 客户端，并已对真实生态 filesystem server 完成实测。本周期补全远程
服务器的 MCP 面，同时让规模选项保持可见。每期独立交付；状态记录在
CHANGELOG（`## [Unreleased]`）。

## Phase 1 — MCP 传输 + 面补全

- **Streamable HTTP 传输 ✅**：profile 行在现有 `command` stdio 形式之外新增
  `url` + `headers` 形式（header 值只引用环境变量*名*， riding
  credentials-by-name）。覆盖 Streamable HTTP 上的 tools/list + tools/call
  生命周期（JSON 与 SSE 双响应、会话 id 回传、卸载时 DELETE）；鉴权只做
  显式 Bearer/自定义 header——不做 OAuth 协商（文档化限制）。
- **Prompts 与 resources ✅**：每个能力一个命名空间只读工具——
  `mcp__<server>__read_resource`（背后 `resources/read`）与
  `mcp__<server>__get_prompt`（`prompts/get`，渲染 `role: text` 行），
  工具描述枚举 server 挂载时提供的清单。prompts 落成工具而非 commands
  接缝，因为命令注册表在 `majo-boot`，而它已依赖 `majo-mcp`——反向边会
  成环。
- 验收 ✅：进程内 fixture 挂载 HTTP MCP server；工具、prompts、resources
  在 JSON 与 SSE 两种响应形态下均可列出可调用；header 环境变量名未设置
  时 fail-loud；stdio 服务器行为不变（套件全绿）。

## Phase 2 — 规模选项（按需）

- **SQLite FTS** 置于 `/api/search` 之后（端点不变；日志量增长前继续以
  按会话条目缓存为默认）。
- **图片卸载**（stretch）：除非出现多模态实际用量，继续推迟。

## 0.5 明确不做

Workflow 编排、agent-team、终端 PTY、ACP/SDK 远程面、桌面端——与 0.4
一致。
