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
| 会话侧栏（列表/新建/标题） | ✅ | 最新在前、事件计数、heuristic 标题 + 每行用户改名（✎）/删除（✕） |
| 对话转写（用户/assistant） | ✅ | 每轮全量重绘（日志即真源） |
| 工具调用与结果渲染 | ✅ | 参数 chip、ok/error 圆点 |
| turn/请求元信息 | 🟡 | REQUEST_HEADER 元行（model、工具列表） |
| 流式 token 显示 | ✅ | SSE `/api/turn/stream`：chunk 帧喂入 live 气泡 |
| 增量追加（免全量重绘） | ✅ | 每个 log/chunk 帧只推一条事件；done 收尾 |
| 消息复制/反馈（👍👎） | ⬜ | 对应 `ui-message-feedback`；后端需反馈接缝 |
| 回答的 Markdown/代码渲染 | ⬜ | 对应 `ui-renderer`；加一个小而安全渲染器 |
| ask-user 行内问题气泡 | ✅ | approval/ask 特性 rail（`ctx.interactions` 队列 → SSE） |
| 审批提示 UI | ✅ | rail 提供 allow/reject（`ctx.interactions`+`tool-approval`） |

## 会话与配置

| dsh 功能 | majo | 说明 |
|---|---|---|
| 会话改名/删除 | ✅ | `PUT/DELETE /api/sessions/:id`（+`/title`）；内存与文件 store；活跃会话自动切换 |
| 模型选择控件 | ✅ | 头部 `<select>` 走 `GET/PUT /api/settings/model`（经 `ctx.settings` 持久化） |
| 斜杠命令（`ui-commands`） | ⬜ | 后端暂无 commands 接缝 |
| 设置（通用/模型/插件） | ✅ | 侧栏 Settings 区：版本/模型/工具/技能事实（`/api/info`） |
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
| Subagent 活动（`ui-subagent`） | 后端已有 subagent | ✅ 侧栏区（近期委派，轮询） |
| Skills 面板（`ui-skill`） | 后端已有 skills | ✅ 侧栏区（`/api/skills`，轮询） |
| Agent team（实验） | 后端未建 | ⬜ |
| 轨迹（`ui-trajectory`） | 会话日志已含一切 | 🟡 经转写 |

## 线契约与面板机制

- 类型单一真源：`WebApiModels` DTO + `SessionEventType` 枚举 → `WebTypesGenerator` → `web-ui/src/types.ts`；后端按同一 DTO 序列化（`@OptionalWire` 配合 `NON_NULL`）。
- 端点：`GET/POST /api/sessions`、`GET/PUT/DELETE /api/sessions/:id`（`PUT …/title`）、`GET/PUT /api/settings/model`、`GET /api/skills`、`GET /api/subagents`、`GET /api/info`、审批/问答决策、SSE `/api/turn/stream`。
- UI 装配仍只靠注册：`features/*` 填 message-renderer/rail/sidebar 槽（经 `FEATURES` 编译期列表）；壳层只渲染槽。

## 搜索/抓取后端（服务端 web 能力族）

- 接缝：`SearchProvider`/`FetchProvider` 注册到 `ctx.web`；首个可用后端（或显式 id）服务 `web_search`/`web_fetch`；无后端结构化失败；provider 文本视为外部/不可信。
- 现役：`web-fetch-http`（匿名 HTML→文本）、`web-search-static`（离线）、`web-search-wiki`（真实无 key Wikipedia API，惰性挂载，`web.yml`）。

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
