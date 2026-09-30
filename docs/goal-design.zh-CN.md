# Goal 系统 — 设计提案（roadmap-0.6 P7）

dsh 0.2.0 引入了 goal 家族（事件溯源 goal 领域 + 同会话自动续跑 driver +
工具 + `/goal` 命令）。本文提出 majo 规模的移植方案，沿用 workflow-v1
模式：关键处忠实机制，majo 单进程循环允许处简化。待定的范围问题列在
文末，实施前需决策。

## 动机与非目标

goal 是让会话跨回合持续工作的持久目标：创建一次，会话就一轮接一轮地向
它推进——同时人类在每个边界都可打断（pause/clear 永远赢，模型无法把
harness 锁死在循环里）。这是单发回合与声明式 workflow 之间缺的一块：
workflow 编排步骤，goal 陈述终点、让模型自己找步骤。

非目标：跨会话 goal、goal 依赖图、独立 goal 存储（goal 与其他一切一样
活在会话日志里）、只读面板之外的 UI。

## 领域模型（dsh `packages/goal/goal`，majo 形态）

每会话一个 goal，事件溯源进日志：

- `Goal` 记录：`goalId`（`goal-<n>`）、`revision`（从 1 起，每次持久
  变更 +1）、`objective`（文本）、`phase`（`active|paused|blocked|
  complete`）、`maxRounds`（默认 256，create 可覆写）、`roundsStarted`
  （仅计入已准入的 goal 轮）、`blockedReason`（blocked 时的
  `{code, message}`）、`createdAt`/`updatedAt`。
- `clear` 写墓碑（goal 置空，`seenGoalIds` 保留 id 使其永不复用）；
  之后可重新 create。
- 持久化：新增持久事件 **`GOAL_CHANGE`**，携带全量快照（`operation`、
  goal 字段、轮数）或 clear 墓碑——即 TodoState/PlanState 的投影模式
  （`majo-session` 的折叠机制是现成的）。投影校验折叠：严格 revision+1、
  合法 phase 迁移（`pause`: active→paused；`resume`: paused/blocked→
  active；`complete`: 任何存活态→complete；`block`: 仅 active→blocked）、
  仅当无存活 goal 时允许 create、id 不可复用。首个非法事件让投影带上
  loud failure，宿主访问即抛错。
- **CAS 防陈旧写**：每个变更调用携带 `(goalId, revision)`；不匹配抛
  `GOAL_STALE_REVISION`（dsh 对齐）。

## 轮 source 管线（majo 的关键接缝）

dsh 给消息打 `source` 标签（`user`/`goal`/…）；`followup()` 缺省 source
即人类。majo 采纳同一词汇：

- `AgentInbox` 的 turn 条目变为 `(text, source)` 对；
  `AgentLoopService.followup(sessionId, text)` 保持 `source=user` 语义
  （既有调用方——jobs/schedule——不变），新增
  `followup(sessionId, text, Map source)` 携带显式 source。
- `USER_MESSAGE` 事件新增 `source` 字段（缺省 `user`；`goal` 轮另带
  `goalId`+`revision`+`round`）。旧日志（无该字段）按 `user` 读。
- `agent/user-submit` **前移到 `TURN_START` 落盘之前**（两者之间无其他
  日志写入）：监听者拒绝时不再留下打开的回合——这正是 driver 干净拒绝
  轮所需要的；hooks 不受影响（其幂等性本就要求如此）。

这条管线在 goal 之外也可复用（未来的 dsh 式 initiator 纪律——jobs/
schedule——走同一字段）。

## 轮 driver（dsh `goal-round-driver`，majo 围栏）

armed/disarmed 激活态是进程内易失状态（新进程 = disarmed——与 dsh 的
重启语义一致；`resume` 重新武装）。`GoalRoundDriver`（监听 goal 变更与
loop 的 `agent/turn-closed`）驱动：

- **准入模型**：空闲邻接 + armed + active + `rounds < maxRounds` 时，
  渲染轮提示（`<goal_round>` 块：objective JSON + `Round: n/max`）并
  `followup(..., source=goal)`。
- **围栏，majo 规模**（dsh 有五道；保留承重的三道）：
  1. *提交时预约校验*：driver 登记待决 attempt；`agent/user-submit`
     收到 goal source 时校验当前 goal id+revision、phase active、armed、
     `round == roundsStarted+1`——不匹配即拒绝（轮号不消耗）。
  2. *竞争让位*：attempt 校验时 inbox 里有任何非 goal 的 turn 条目，
     driver 即让位（attempt 丢弃，等待重新评估）——任何人类 prompt 的
     优先级都高于 goal，直到会话再次空闲。
  3. *maxRounds*：准入时 `rounds >= maxRounds`，driver 以
     `round-limit` 阻断 goal 而不再排队。
  已文档化的简化：不做逐消息 id 去重（majo inbox 在内存中）、不做
  flush 检查点（单进程，日志本就逐事件持久）。
- **host-pause vs model-pause**：`GoalService.pause(byHost=true)`（来自
  `/goal pause` 或 API）额外调用 `loop.abort(sessionId)`——在跑回合以
  `aborted` 收尾，goal 随之 paused。模型在自己回合内经工具发起的 pause
  正常收敛。用 majo 新落地的最小取消体系实现 dsh 对齐。
- 回合以 `aborted` 收尾且 goal attempt 在途 → attempt 丢弃、goal 保持
  armed（新一轮可随时启动）；maxSteps 硬失败同样保持 armed，driver 在
  下次空闲时重新评估。

## 工具（dsh `tool-goal` + authority）

三个工具 + 一个静态系统段（`goal-tools`：下列规则，内插 N 阈值）：

- `get_goal` — 快照或"无活跃 goal"。
- `create_goal` — **requireDirectHuman**：仅当打开的回合含真实用户消息
  （`source=user`）时有效。模型不能自行启动 goal。
- `update_goal`，按 action：
  - `edit`（objective/maxRounds）— requireDirectHuman；phase 不可变。
  - `pause` / `resume` — requireDirectHuman；模型 resume 已暂停的 goal
    直接拒绝（`GOAL_TOOL_RESUME_PAUSED`）。
  - `complete` — 直接人类**或**处于当前 goal 轮内（authority = 打开的
    回合匹配该 goal 的 id/revision/round）。
  - `blocked {code, message}` — 同 complete，另加硬下界：
    `roundsStarted >= blockedAfterConsecutiveRounds`（默认 3；人类请求
    绕过下界）。majo 保留 dsh 的诚实语义：运行时只数轮，"同一条件持续"
    的判断归模型。
- 所有变更携带 `(goal_id, revision)`（CAS），在无会话绑定的回合外调用
  一律 loud 失败。
- **wrapup**：自主（goal 轮 authority）complete/blocked 成功后，工具
  注入 `<goal_complete>`/`<goal_blocked>` CONTEXT_NOTE（objective/理由
  + 收尾消息指引）——majo 的 `loop.inject` 正好落在同一回合的下一个步
  边界。人类发起的变更不注入（dsh 对齐）。

`requireDirectHuman` 读打开回合的 `USER_MESSAGE` source——与 driver 用
的是同一条轮 source 管线，无需额外的 initiator 机器。

## `/goal` 命令（dsh `command-goal`，经 `majo-boot` CommandRegistry）

`/goal`（show）、`/goal <objective>`（create）、`/goal edit <objective>`、
`/goal pause`、`/goal resume`、`/goal clear`。人类命令天然构成
direct-human authority（命令从 UI/控制台发起，其变更加 `byHost=true`
落盘）。composer 附件先行提交不在范围内（majo composer 没有 goal 附件
流）。

## 存储与 API 面

- 事件：`GOAL_CHANGE`（+ `USER_MESSAGE.source` 字段）——都进类型化投影
  注册表；折叠永不进模型历史（轮文本本身就是 user message）。
- 可选 Phase C：`GET /api/sessions/{id}/goal`（供 UI 的快照）+ 只读
  goal chip；机制本身不依赖。

## 阶段

- **Phase A**（核心）：轮 source 管线（inbox/source 字段/user-submit
  前移）+ `GoalService` + `GOAL_CHANGE` 投影 + `get/create/update_goal`
  工具 + `goal-tools` 系统段。
- **Phase B**（自主）：轮 driver（三道围栏）+ wrapup 注入 + host-pause
  abort + `/goal` 命令。
- **Phase C**（可选）：goal 快照 API + UI chip。

## 验收

- CAS：陈旧 revision 的更新 loud 失败；折叠拒绝非法迁移；clear 墓碑
  禁止 id 复用。
- authority：无人类回合的 create/edit/pause/resume 失败；goal 轮内的
  complete/blocked 成功；低于 N 轮的 blocked 失败；模型 resume 已暂停
  goal 失败。
- driver：armed active goal 产出精确渲染形态的轮提示；竞争的人类
  followup 让 driver 让位；轮上限以 `round-limit` 阻断；host pause 中止
  在跑回合；重启后 goal 保持 disarmed。
- 一切都走日志：会话事件 + 进程内激活态之外无任何 goal 状态。

## 待定问题（实施前决策）

1. **范围**：全量移植（Phase A+B，含 `/goal`——推荐）vs 仅 Phase A
   （状态板，不自动续跑）vs A+B 但不要 `/goal`。
2. **authority 严格度**：完全照搬 requireDirectHuman（推荐——模型不能
   自建/自停/自恢复 goal）vs 放宽（模型可自主建 goal；更小，但失去了
   让 goal 安全的人类在环保证）。
3. **blocked 下界**：保持 3（推荐）vs 改为纯配置项、换默认值。
