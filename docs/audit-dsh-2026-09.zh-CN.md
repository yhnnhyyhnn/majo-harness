# dsh 参考项目增量审计 — 2026-09-18

上次审计（2026-09 中旬，产出 roadmap-0.3）之后的跟进。参考项目此后前进
约 1,700 个提交、两个发布（`dsh-v0.1.5-rc.2`、`dsh-v0.1.6-alpha.1`）。
本文记录变化、majo 的响应调整，以及保留的有意分歧与未来候选。每条结论
标注：**已采纳**（代码已落地）、**有意分歧**（保留）、**候选**（未来
工作）。

## 核心机制：无变化（移植仍对齐）

- **agent-loop / 双 inbox**：入口仍恰好 `followup` / `steer` / `inject`；
  wake 闩锁与收敛语义未变。
- **审批审计**：仍为 `ask | never` 策略 + 持久 asked/decided 事件对。
- **jobs / schedule**：契约未变。
- **llm-replay / llm-mock-server**：fixture 词汇未变（mock server 的故障
  词汇比 majo 的 `FaultLlmServer` 脚本更丰富——候选）。

## 本周期已采纳

1. **工具调用超时**（dsh `guard` timeout-policy，参考项目默认启用）：
   `tools` 插件新增 `toolTimeoutSeconds`——挂死的调用现在返回清晰的
   超时错误，不再永远卡住回合。web profiles 启用为 600 秒；库默认关闭。
2. **工具结果裁剪形态**（dsh `compaction-tool-result-pruner`）：被裁剪
   的结果保留标记行 + **头部（4096）/尾部（1024）**，不再整段吞掉；
   阈值对齐 dsh 的 8192（`pruneChars` 默认 4000 → 8192）。
3. **MCP stdio 环境脱敏**（dsh `scrubbedParentEnv`）：拉起的 MCP server
   只能看到白名单内的环境变量（PATH/HOME/系统基础项）+ 行内显式
   `env`——环境变量里的凭证不再可能泄漏进 server 进程。

## 有意分歧（保留）

- **MCP prompts 桥接**——参考项目完全不桥接 `prompts/*`；majo 暴露
  `mcp__<server>__get_prompt`。超集，保留。
- **MCP resources 按服务器只读工具**——参考项目改为三个*共享*工具
  （`list_mcp_resources` / `list_mcp_resource_templates` /
  `read_mcp_resource`，均带 `server` 参数），并把 server `instructions`
  发布为 system-prompt 段。majo 的按服务器 `read_resource` 在当前规模
  更简单；对齐列为候选。
- **`auto` 作为审批策略值**——参考项目把 `auto` 保留给权限*预设*
  （背靠实验性外部评审层）；majo 的会话策略 `ask|never|auto` 是
  fail-closed 且已文档化。保留。
- **会话格式 v1**——参考项目已到 v3（三步迁移链、worker 隔离校验、
  可选 zstd）。majo 的 v1 + header + 迁移链机制正是为将来演进准备的；
  目前无 v2 可迁移。
- **MCP env 按名引用**——majo 在挂载期解析 `${VAR}` 名；参考项目取
  显式值叠在脱敏后的环境上。majo 现在同样脱敏环境（见上），并保留
  按名引用——凭证永不进入 profile 文件。

## 未来周期候选（参考项目有、majo 缺）

- **MCP 加固 ✅ 部分采纳于 2026-09-18**：重连策略（指数退避 + 尝试预算 +
  稳定窗重置；启动失败由独立的 `failOnStartupError` 管控）、服务器名
  校验（`[A-Za-z0-9_-]{1,32}`）。仍是候选：server `instructions` 作为
  system-prompt 段与共享 resource 工具。
- **`context` 族 ✅ 已于 2026-09-18 采纳（`majo-context`）**：请求上下文
  注入插件——工作区指令（AGENTS.md / CLAUDE.md 加载）与时间上下文，以
  一次性持久 `CONTEXT_NOTE` 事件落地；`@file` 提及与跨会话快照仍是
  候选。
- **`spill` 族 ✅ 已于 2026-09-18 采纳（`majo-spill`）**：超长工具结果
  外置存储 + 定位器（`spill_read` 取回），内联只留预览 + 取回指引；
  经 `maxInlineBytes` 选择性启用（web profiles：16 KiB），存储失败
  fail-open，取回豁免再溢出。
- **`ssh` 族**：`fs`/`subprocess`/`sandbox` 提供者经一条 OpenSSH 连接
  指向远程主机（"单一执行世界"）。
- **`ptc-runtime`**：程序化工具调用（`run_code`——模型写一个程序替代
  N 次调用）。
- **`guard` 重复调用提醒**（advisory，很小）。
- **`hooks` 兼容**：在 waterfall 面上运行 Claude Code / Codex 的
  `hooks.json` 命令钩子。
- **`llm-mock-server` 故障词汇**：stall、错误 Content-Type、上下文
  超限、配额、加权 random——比 majo 的故障脚本丰富。
- **更大的架构主题**（各自一个周期）：`typert` + `api` Remote 层
  （类型化 Client→Host RPC）、`preset`（每会话 agent 组合）、
  `storage`/`workspace`/`webhook`/`feedback`/`identity`、`lsp`、
  `extensions`（agent 可改的运行时）、`bundle` profile 分层、
  `experimental/agent-team`。
