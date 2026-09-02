# 与 deepseek-harness 的 Web 功能对齐

<a href="web-parity.md">English</a> | 中文

参考客户端（`packages/client`）把 UI 拆成能力模块（`ui-chat`、`ui-session`、
`ui-tool`、`ui-approval`、`ui-user-questions`、`ui-model-selection`、
`ui-commands`、`ui-settings`、`ui-plan`、`ui-goal`、`ui-jobs`、`ui-sidebar`、
`ui-theme`…）。本文追踪 `majo-web`（`web-ui/` 下的 React/Vite 应用）离这一
功能面还差多少，以及每项功能需要 Java 后端给什么。

图例：✅ 已交付 · 🟡 部分 · ⬜ 未实现

## 对话核心

| dsh 功能 | majo | 说明 |
|---|---|---|
| 会话侧栏（列表/新建/标题） | ✅ | 最新在前、事件计数、heuristic 标题 |
| 对话转写（用户/assistant） | ✅ | 每轮全量重绘（日志即真源） |
| 工具调用与结果渲染 | ✅ | 参数 chip、ok/error 圆点 |
| turn/请求元信息 | 🟡 | REQUEST_HEADER 元行（model、工具列表） |
| 流式 token 显示 | ⬜ | 需要 SSE / 分块 turn API |
| 增量追加（免全量重绘） | ⬜ | 需要 `sinceSeq` 参数或流 |
| 消息复制/反馈（👍👎） | ⬜ | 对应 `ui-message-feedback`；后端需反馈接缝 |
| 回答的 Markdown/代码渲染 | ⬜ | 对应 `ui-renderer`；加一个小而安全渲染器 |
| ask-user 行内问题气泡 | ⬜ | `ui-user-questions`；后端 `ctx.interactions.ask` 已有——缺轮内通道 |
| 审批提示 UI | ⬜ | `ui-approval`；后端 `ctx.interactions`+`tool-approval` 已有——缺队列+UI 通道 |

## 会话与配置

| dsh 功能 | majo | 说明 |
|---|---|---|
| 会话改名/删除 | ⬜ | 需要 DELETE/PATCH 端点 |
| 模型选择控件 | ⬜ | `ui-model-selection`；后端已支持多模型注册——缺按会话模型设置 |
| 斜杠命令（`ui-commands`） | ⬜ | 后端暂无 commands 接缝 |
| 设置（通用/模型/插件） | ⬜ | `ui-settings`；`ctx.settings` 已存在 |
| 主题切换 | ⬜ | CSS 变量化后成本极低 |
| 连接状态与重连横幅 | ✅ | offline 横幅 + Retry |

## 能力面板（依赖后端接缝）

| dsh 功能 | majo 接缝状态 | UI |
|---|---|---|
| Plan 模式（`ui-plan`） | 后端未建 | ⬜ |
| Goals（`ui-goal`） | 后端未建 | ⬜ |
| Jobs（`ui-jobs`） | 后端未建 | ⬜ |
| Schedule（`ui-schedule`） | 后端未建 | ⬜ |
| Workflow（`ui-workflow-run`） | 后端未建 | ⬜ |
| Subagent 活动（`ui-subagent`） | 后端已有 subagent | ⬜ |
| Skills 面板（`ui-skill`） | 后端已有 skills | ⬜ |
| Agent team（实验） | 后端未建 | ⬜ |
| 轨迹（`ui-trajectory`） | 会话日志已含一切 | 🟡 经转写 |

## 建议顺序

1. **真实模型对话打磨** —— 流式 + 增量追加（SSE `/api/turn`）、
   markdown-lite 渲染、消息复制。后端只需在 `WebMain` 加一条分块/SSE 路径，
   其余纯前端。
2. **模型选择器** —— `GET/PUT /api/settings/model`，以 `ctx.settings` 为底，
   按会话覆盖项以持久事件记录。
3. **UI 里的审批与 ask-user** —— 给 `ctx.interactions` 加队列驱动通道
   （服务端事件 → 浏览器），实现 `ui-approval`/`ui-user-questions` 等价物
   （接缝后端已就绪）。
4. **随后端接缝落地而补能力面板**（plan/goals/jobs/subagent/skills…）。
