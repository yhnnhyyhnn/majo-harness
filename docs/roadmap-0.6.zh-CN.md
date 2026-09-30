# Roadmap 0.6（审计第二轮对齐：fs 写工具族、用量计量、取消体系、cron）

dsh 审计第二轮（`docs/audit-dsh-2026-09.zh-CN.md`，2026-09-30，对照
dsh 0.2.0-rc.1）按实用价值排出了剩余对齐债。本周期落地四个范围明确的
优先项；更深的主题保持显式可见。状态以 CHANGELOG（`## [Unreleased]`）
为准。

## P1 — fs 写工具族（价值最高、成本最低）

- **`write_file`** ✅：创建/覆盖 UTF-8 文件（自动建父目录）。
- **`edit_file`** ✅：精确字符串替换，默认要求唯一匹配（`replace_all`
  显式开启）——在 provider 的读/写之上组合，SSH 世界免费获得。
- **`glob`** ✅：root 下递归、支持 `**` 的模式搜索（本地 provider 从
  浅层列举升级；SSH provider 仍走 `find`）。
- **`grep`** ✅：正则内容搜索，带 include-glob 过滤与条数上限——
  `FsProvider.grepText` 接缝方法（本地：`Files.walk`；SSH：远端
  `grep -rn`）。
- 命名沿用 majo 家族惯例（`read_file`/`write_file`/`edit_file`），
  `glob`/`grep` 与 dsh 同名；read 命名分歧保持文档化。

## P2 — token 用量计量（LLM 词汇最小切片）

- **`TokenUsage`** ✅：input/output（provider 报告时含
  cacheRead/cacheWrite）挂上 `ChatResponse`，可空；保留双参构造形状，
  既有调用方不受影响。
- **OpenAI 兼容解析** ✅：非流式 `usage` 对象与流式末块的 `usage`
  都映射进来。
- **持久化记录** ✅：ASSISTANT_MESSAGE 事件在已知时携带该轮的用量
  字段——每次请求的用量可观测，估算器无需改动。
- 内容块（Text/Reasoning/Image/File）与 StreamChunk 协议**推迟**：
  没有 multimodal provider 落地之前是死重；本切片以零 API 成本为将来
  留门。

## P3 — 最小取消体系

- **类型化 TURN_END** ✅：持久 `reason` 字段——`completed`（正常）、
  `error`（回合失败；此前回合会保持打开）、`aborted`、`max_steps`。
- **`abort(sessionId)`** ✅：协作式取消——步循环在步边界与工具分发前
  检查标志，以 `aborted` 收回合，返回已有答案。
- **HTTP 面** ✅：`POST /api/sessions/{id}/abort`。
- 已文档化限制：不做请求中途/工具中途打断（dsh AbortController 对齐
  仍是未来项）；被中止回合后 pending inbox 照常收敛。

## P4 — cron 计划

- **Vixie 五字段解析器** ✅（`CronExpression`）：分 时 日 月 周，支持
  `*`、列表、区间、步进；显式 IANA `timezone`；六字段输入以清晰错误
  拒绝（dsh 对齐）。
- **`schedule_create`/`schedule_update` 新增 `cron` + `timezone`** ✅：
  持久化存储（新增事件字段），创建/更新时计算下次触发，每次触发后与
  重启 rescan 时重新计算。

## P5 — hooks 兼容桥 ✅

在 waterfall 面上运行 Claude Code / Codex 的 `hooks.json` 命令钩子
（`majo-hooks`）：`PreToolUse`（阻断门——exit 2 / `continue:false` /
`permissionDecision: deny` 使调用变为模型可见错误；allow 与 ask 仅为
建议值）、`UserPromptSubmit`（在落盘前大声拒绝，或将 stdout 上下文注入
提交文本——模型所见即一条已记录消息）、`Stop`（回合后上下文落为持久
CONTEXT_NOTE）。dsh 匹配器方言逐字移植（纯词+竖线为字面量交替、其余为
非锚定正则、match-all 哨兵、无效正则不匹配任何东西）。每次调用追加持久
`HOOK_INVOKED`/`HOOK_RESULT` 审计对（决策、按退出码契约定界的 stderr、
墙钟时长）；无法运行的钩子 fail-open；配置为 `hooks.json` 文件路径
（文件缺失即关闭，同 skill-files 惯例）或内联。配套接缝：
subprocess/shell 命令携带可选 stdin 载荷（异步写入、容忍断管），loop
新增 `agent/user-submit`（落盘前替换或拒绝）与 `agent/turn-closed` 事件，
`prompt`/`agent`/`http` 钩子类型与参考桥一致地解析后跳过。

## P6 — skill 与 PTC 深化 ✅

- skill：scope 链（project/custom/user）+ 优先级 rank + 单 `skill`
  工具 + 持久目录消息。
- PTC：`run_code` 程序回调宿主工具的协议（fd 通道 + 嵌套 dispatch +
  逐调用 dispatch log）。

## P7 — goal 系统（先设计后动工）

事件溯源 goal 领域 + 自动续跑 driver + goal 工具。机制上吸引但是一整个
周期；动工前需要设计提案（类似 workflow v1）。

## 明确不在 0.6 范围

宿主产品层——storage/workspace/session-query/preset/desktop、
webhook/feedback/identity——在当前规模下与 majo 单用户本地定位冲突。
多模态内容块、重试执行器、请求中途取消同样不做。会话格式
v2/v3/v4 保持记录在案的分歧（迁移链机制正是为需要时准备的）。
