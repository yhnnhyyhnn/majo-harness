# Roadmap 0.5（MCP 传输 + 规模化）

0.4 交付了持久性加固（会话文件代际、LLM 故障注入、工具结果裁剪）与 stdio
MCP 客户端，并已对真实生态 filesystem server 完成实测。本周期补全远程
服务器的 MCP 面，同时让规模选项保持可见。每期独立交付；状态记录在
CHANGELOG（`## [Unreleased]`）。

## Phase 1 — MCP 传输 + 面补全

- **Streamable HTTP 传输**：profile 行在现有 `command` stdio 形式之外新增
  `url` + `headers` 形式（header 值只引用环境变量*名*， riding
  credentials-by-name）。覆盖 Streamable HTTP 上的 tools/list + tools/call
  生命周期；鉴权只做显式 Bearer/自定义 header——不做 OAuth 协商（文档化
  限制）。
- **Prompts 与 resources**：`prompts/list` + `prompts/get` 桥接进既有
  skills/commands 接缝；`resources/list` + `resources/read` 落在只读工具
  （`mcp__<server>__read_resource`）之后。具体形态在实现时对着真实
  server 敲定。
- 验收：测试内（进程内 fixture）挂载 HTTP MCP server；工具、prompts、
  resources 可列出可调用；header 环境变量名未设置时 fail-loud；stdio
  服务器行为不变。

## Phase 2 — 规模选项（按需）

- **SQLite FTS** 置于 `/api/search` 之后（端点不变；日志量增长前继续以
  按会话条目缓存为默认）。
- **图片卸载**（stretch）：除非出现多模态实际用量，继续推迟。

## 0.5 明确不做

Workflow 编排、agent-team、终端 PTY、ACP/SDK 远程面、桌面端——与 0.4
一致。
