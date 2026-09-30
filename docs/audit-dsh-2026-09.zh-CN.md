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

---

# 第二轮 — 2026-09-30：对照 dsh 0.2.0-rc.1 的深度差异分析

第一轮以来参考项目前进约 **1,961 个提交、三个发布**（0.1.5-rc.3 →
0.1.7-rc.x → 0.2.0-rc.1），包数 58 → 61。按五个领域（核心循环 /
LLM+上下文 / 工具 / 会话+workflow / web+运行时）并行深挖后汇总如下。
上游重心已转向**内核硬化**（goal 系统、取消体系、多模态 LLM 词汇）与
**宿主产品层**（storage/workspace/session-query/hooks/webhook/preset/
desktop）——后者在 majo 的规模定位下有意不追。

## 一、上游新增、majo 完全空白

- **Goal 家族**（本轮最大新主题）：事件溯源 goal 领域
  （`packages/goal/goal/src/index.ts` — phase 状态机、CAS revision、
  maxGoalRounds）、自动续跑 driver（`goal-round-driver`）、
  `get_goal`/`create_goal`/`update_goal` 工具（`tool-goal`）、`/goal`
  命令（`command-goal`）。majo 全库无对应物，roadmap 亦未提及。
- **取消体系**：类型化 `TurnEndReason` 含 `aborted(cause)` +
  `CancelOptions.keepInbox`（`packages/core/session/src/types.ts`）、abort
  时为未启动调用落合成 `TOOL_ABORTED_BEFORE_DISPATCH` 结果保重放有效
  （`core/agent-loop/src/tool-calls.ts`）、归档准入自动取消
  （`core/agent/src/archive-admission.ts`）。majo 循环零 cancel/abort。
- **多模态 + 计量 LLM 词汇**：内容块（Text/Reasoning/Image/File）+
  StreamChunk 流协议（`packages/llm/llm/src/types.ts`）、含
  cacheRead/cacheWrite 的 `TokenUsage`、图像 token 精算
  （`llm-deepseek/src/image-tokens.ts`）、Files API、模型发现、独立重试
  执行器（`packages/llm/llm-retry`）。majo `ChatMessage` 只有 String
  content，`ChatResponse` 无 usage，无重试。
- **宿主产品层**：`storage`（非会话 KV 持久化 + schema 校验 + 变更事件，
  `packages/storage/storage-domain/`）、`workspace`（持久项目注册表、
  归档不删除）、`session-query`（SQLite FTS5 + 谱系 trace，
  `packages/session-query/session-query-sqlite/`）、`hooks` 兼容桥
  （直接跑 Claude Code/Codex 的 hooks.json，`packages/hooks/hook-protocol/`）、
  `webhook`（签名 GitHub 摄入）、`feedback`、`identity`（匿名 UUID）、
  `preset`/`persona`（每会话 agent 组合）、desktop（Electron 壳）。
- **扩展工具族**：browser-use/computer-use（experimental，能力槽位注册表
  + MCP provider，未打包）、terminal PTY（6 工具，
  `packages/terminal/tool-terminal/`）、lsp（单工具 4 只读操作）、
  document office→pdf 服务、attachment 准入 + `read_image`。

## 二、两边都有、上游明显更深（对齐债）

- **FS 写工具族——实用性最硬的缺口**：majo 的 `majo-fs` 只有
  `read_file` + `git_status`；dsh 有 read/write/edit/glob/grep/
  read_image/str_replace_editor 全家（`packages/fs/tool-fs/`）。
  **majo 的模型目前什么文件都写不了。**
- **双 inbox**：持久 splice 事件 + 消息 id + 去重
  （`core/agent-loop/src/inbox.ts`）vs majo 内存字符串队列
  （`AgentInbox.java`）。
- **turn 终止原因**：类型化 map（error/max-tokens/aborted/forked）vs
  majo 无字段的 TURN_END。
- **审批**：策略持久事件 + abort 撤问 + 迟到答案丢弃
  （`packages/interaction/user-approval/`）vs ask/auto/never 事件对、
  无撤销。
- **重复调用提醒**：深键规范化、被拒调用也计数、用户插话重置链
  （`packages/guard/repeat-tool-reminder/`）vs 简单计数。
- **压缩**：区域选择（定价尾段保留 + tool 配对保护）+ start/end 事务 +
  检查点（`packages/compaction/compaction-basic/src/region.ts`）vs
  整史一次性摘要。
- **指令上下文**：用户全局 + 项目链、fs 操作触发刷新、65,536 字节预算
  （`packages/context/agent-instructions/`）vs 一次性根目录注入。
- **skill**：scope 链 + 优先级 rank + bundled + 单 `skill` 工具 + 持久
  目录消息（`packages/skill/tool-skill/`）vs 目录扫描 + 2 工具。
- **PTC**：程序内 `tools.name(args)` 回调宿主工具（嵌套 dispatch）、
  沙箱集成、升级审批（`packages/core/tools/src/ptc.ts`）vs majo
  自述"v1 不可调工具"（`PtcService.java`）。
- **jobs**：绝对字节偏移 ring buffer + 任意偏移读 + 归档联动 kill
  （`packages/jobs/jobs-local/src/ring.ts`）vs 尾部截断。
- **schedule**：every/daily/weekly/**cron**（Vixie 五字段 + IANA 时区）+
  投递历史 + Host 级持久化 + 冷唤醒
  （`packages/schedule/schedule/src/types.ts`）vs after/every/at +
  daily/weekly 糖，无 cron。
- **subagent**：continuable 子代理（send_message/interrupt/list，
  `packages/subagent/tool-subagent-control/`）+ experimental agent-team
  （mailbox/任务 DAG/roster）vs 一发即弃的同步 `delegate_task`。
- **会话格式**：上游已到 **v4**（v3→v4 已落地：
  `packages/session/session-format-v3-to-v4/README.md`）——第一轮"v3"
  的记录当时已过时；另有检查点策略与持久投影缓存为 majo 所缺。

## 三、有意分歧（保留）

- **workflow 形态相反**：majo = 声明式 YAML 步骤 + 虚拟线程并行组 +
  **in-process resume**；dsh = 模型即席写 JS 跑在 PTC 运行时
  （`agent()/parallel()/pipeline()`），无 resume。两套语义互不映射；
  majo 在 resume 上先行。
- **majo 独有优势**：循环内凭据脱敏（`safe()`）、
  `ALLOW_MODEL_TRIGGER_TAG` 审批豁免、OpenAPI 契约 + metrics + 生成式
  web 类型（防漂移）、spill fail-open + 注册式 `spill_read`、headroom
  半预算钳制。
- **命名**：`read_file`/`run_shell`/`run_command` vs dsh `read`/`bash`
  ——为会话/测试稳定保留，已文档化。
- **成立简化**：maxSteps=8 硬上限、steer-while-idle 退化为 followup、
  pending 不持久、abort 仅在步边界生效（不做请求中途取消）。

## 处置

第二轮结论汇入 `docs/roadmap-0.6.zh-CN.md`：P1 fs 写工具族、
P2 TokenUsage、P3 最小取消体系（类型化 TURN_END + abort）、
P4 cron schedule；P5 hooks 桥、P6 skill/PTC 深化、P7 goal 系统设计
研究。宿主产品层（storage/workspace/session-query/preset/desktop）与
majo 单用户本地定位有张力，明确不做。
