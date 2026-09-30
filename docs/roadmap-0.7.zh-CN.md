# Roadmap 0.7（Round 2 剩余对齐债：压缩区域选择、可续聊子代理）

截至 0.9.0，dsh 审计第二轮已全部处置（P1–P7 交付）。剩下的是该轮
"两边都有、上游更深"的尾部——majo 已有能力内部的对齐债。本周期先取
价值最高的一项；其余排队或记录为有意分歧。状态以 CHANGELOG
（`## [Unreleased]`）为准。

## P1 — 压缩区域选择 ✅

整史摘要是 majo 压缩最弱的一环——而 goal 会话（0.9.0）现在会把更多
轮次推进压缩。dsh 选择可压缩区域
（`packages/compaction/compaction-basic/src/region.ts`）：majo 按自身
规模采纳同一形态——

- **定价尾段保留**：摘要只覆盖到切点为止的区域；定价为
  `retainTokens`（新配置，默认 maxTokens/4）的尾段在派生历史中逐字
  保留。既有 `upToSeq` 字段终于有了消费方：派生器折叠 CONTEXT_COMPACTION
  时丢弃 `upToSeq` 及之前事件贡献的消息、前插摘要——切点之后的事件
  （在压缩事件之前落盘）得以存活。
- **tool 配对保护**：切点绝不拆开 assistant 工具轮——回退到该轮开头的
  ASSISTANT_MESSAGE 之前。
- **区域化摘要请求**：摘要请求只携带区域的派生消息（更省，且摘要
  无法对未见过的尾段产生幻觉）。
- 多次压缩可组合（每次折叠保留更新的尾段）。

## P2 — 可续聊子代理 ✅

dsh 的子代理可跨回合延续（`tool-subagent-control`：
send_message/interrupt_agent/list_agents；continuable children 以父
历史播种）。majo 的 `delegate_task` 一发即弃。majo 形态：子会话
持久化；delegate 返回 `session_id`；后续消息经 `loop.followup` 进子
会话；list/interrupt 补全。

## 有意分歧（记录在案，不排期）

- **双 inbox 持久化**：majo 的 pending inbox 按设计留在内存（足够
  耐用——其余一切都走日志；崩溃只丢排队通知）。
- **jobs ring buffer**：8,000 字符尾部截断在 majo 的任务规模下足够；
  任意偏移读不做。
- **指令上下文链**：单根注入 + 两层去重在本地足够；项目链刷新机制
  不做。
- **审批撤问**：同步阻塞 ask 已 fail-safe（超时即拒，默认 30s）；
  abort-signal 撤问增益有限。
- **重复提醒深化** ✅（被拒调用计数、用户插话重置、per-session 链、规范化
  参数）——顺路在 0.9.3 完成，连同 goal 空闲创建自驱修复。
- **多模态内容块 / 请求中途取消 / 重试执行器**：与 0.6 相同——在
  multimodal provider 或硬需求出现前不做。
