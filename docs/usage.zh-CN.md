# 使用指南

majo-harness v0.7.0 快速上手与功能走查。

[English](usage.md) | 中文

## 快速开始

```bash
# 构建
mvn -B -ntp clean verify -DskipTests

# 启动 web UI（离线 mock 模型）
java -jar majo-web/target/majo-web-0.7.0.jar

# 或接真实模型端点
java -jar majo-web/target/majo-web-0.7.0.jar --profile web

# → 打开 http://127.0.0.1:8787
```

## 连接模型

自带的 `web.yml` profile 有两个模型：

- **mock**——离线确定性（无 key 测试用）
- **kilo-free**——经 OpenAI 兼容网关 `api.kilo.ai` 的免费 tier

要用自有端点（LM Studio / Ollama / vLLM / 任何 OpenAI 兼容 API），在
profile 里加一行：

```yaml
- id: llm-my-model
  name: llm-openai
  config:
    name: my-model
    model: my-model-id
    baseUrl: http://localhost:1234/v1   # LM Studio / Ollama / 等
```

在头部模型选择器里选，或用 `/model my-model`。

## 32 个工具

| 工具 | 功能 |
|---|---|
| `calc` | 整数运算（门控→审批卡） |
| `read_file` | 读文件（门控） |
| `write_file` | 创建/覆盖文件（门控） |
| `edit_file` | 文件内精确字符串替换，唯一性校验（门控） |
| `glob` | 按 glob 模式递归搜文件（`**` 跨目录）（门控） |
| `grep` | 正则内容搜索，带 include 过滤与条数上限（门控） |
| `run_shell` / `run_command` | Shell/命令执行（门控） |
| `run_background` | 后台执行 + `job_output/list/kill` |
| `web_search` / `web_fetch` | 网页搜索与抓取 |
| `delegate_task` | 扇出 scoped 子代理 |
| `send_message` / `list_agents` / `interrupt_agent` | 续聊子代理（follow-up）、列出存活子代理、中断在跑子轮 |
| `todo_write` | 替换会话待办列表 |
| `exit_plan_mode` | 提交计划等审批 |
| `schedule_create/list/update/delete` | 定时提醒，daily/weekly 糖与 Vixie cron（`cron` + `timezone`） |
| `run_code` | **PTC**：在 Node.js 进程执行 JavaScript；程序内经 `await tools.<name>(args)` 调用宿主工具 |
| `spill_read` | 取回外置存储的超长工具输出 |
| `workflow_run` | 执行命名工作流 |
| `skill` | 加载指定技能的完整指令（目录随系统提示注入） |

## 关键功能

### @file 提及

在 composer 里输入 `@` 触发工作区文件补全。选中后文件内容注入为持久
上下文注记——模型无需工具往返即可读到。纯文本（二进制拒绝），64k 字符。

### 目标（goal）

`/goal <objective>` 创建持久会话目标；harness 随后自主一轮接一轮推进
（`<goal_round>` 用户消息）。人类始终掌舵：`/goal pause`（中止在跑
轮）、`/goal resume`、`/goal edit`、`/goal clear`。模型可在轮内宣告
完成，或在同一阻塞条件持续 ≥3 轮后报告 blocked（可配置）。CAS
revision 防陈旧写；重启后 goal 保持 disarmed，需人类 resume。

### 工作流

在 `workflows/*.yml` 定义多步编排：

```yaml
name: my-task
description: 一行描述
steps:
  - id: fetch
    kind: delegate              # scoped 子代理
    prompt: "读 {{args.path}} 并总结。"
    allowedTools: [read_file]
  - id: analyze
    kind: turn                  # 运行子会话中的模型回合
    prompt: "分析：{{steps.fetch.output}}"
onFailure: abort
```

然后：`/workflow my-task {"path": "README.md"}`，或让模型用 `workflow_run`。

### PTC（`run_code`）

代替 N 次工具调用，写一段 JS：

```json
{"code": "const d = [3,1,2]; d.sort(); console.log(JSON.stringify(d));"}
```

在独立 Node.js 进程中执行（30s 超时）。适用于计算、JSON 变换、或任何
否则需要多次 LLM 往返的逻辑。

### 上下文注入

`context` 插件（默认挂载）在每个会话的首个回合注入工作区指令
（`AGENTS.md` / `CLAUDE.md`）和当前时间，以持久上下文注记落地。

### 溢出外置

超长工具结果（> 16 KiB）外置存储 + 定位器。模型只看到预览 +
`spill_read` 指令。与压缩裁剪互补（裁剪管历史旧结果，spill 管当前）。

### 会话管理

- `/compact`——压缩会话历史为持久摘要
- `@file`——无需工具即可引用文件
- `/plan`——计划模式 + 审批卡
- `/delegate <task>`——一次性子代理委派
- `/status`——harness 计数器
- Trajectory 视图——回合分组的事件台账

### MCP 服务器

经 MCP 添加外部工具（stdio 或 Streamable HTTP）：

```yaml
- id: mcp
  name: mcp
  config:
    servers:
      filesystem:
        command: npx
        args: ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
      remote:
        url: https://mcp.example.com/mcp
        headers:
          Authorization: Bearer ${MY_TOKEN}
```

工具以 `mcp__<server>__<tool>` 命名出现。审批、审计、工具目录自动覆盖。

### 远程执行（SSH）

把 `fs` 和 `subprocess` 指向远程主机：

```yaml
- id: fs
  name: fs
  config:
    ssh:
      host: myserver
      user: deploy
```

文件操作和命令在远程执行；"本地路径绝不从远程路径字符串推断"的不变量
成立。需要免密 SSH；仅 POSIX 远程主机。

## 安全模型

- 敏感工具（`calc`、fs 家族 `read_file`/`write_file`/`edit_file`/`glob`/
  `grep`、`run_shell`、`run_command`、`web_search`、`web_fetch`、
  `workflow_run`）**默认门控**——模型调用时
  你看到审批卡，Allow/Deny。
- 会话级策略：`session.approval.<tool-id>` = `ask|never|auto`。
- 审批审计对（`APPROVAL_REQUESTED`/`APPROVAL_DECIDED`）为每次门控调用
  持久写入会话日志。
- MCP env/header 值只引用环境变量**名**——凭证永不进入 profile 文件。
- MCP stdio 子进程获得脱敏环境（仅白名单项）。
- 运行中的回合可协作取消：`POST /api/sessions/{id}/abort` 在下一个步
  边界以 `aborted` 收回合（不做请求中途打断）。每个完成的回合都以
  持久 `TURN_END` 原因收尾（`completed`/`aborted`）。
- **Hooks**：把 `hooks` 插件指向 Claude Code / Codex 的 `hooks.json`
  （`hooks: {path: hooks.json}`），即可在三个点运行命令钩子——
  `PreToolUse`（阻断门：exit 2 / `continue:false` /
  `permissionDecision: deny` 使工具调用变为模型可见错误）、
  `UserPromptSubmit`（在落盘前拒绝提交，或把 stdout 上下文附加到提交
  文本）、`Stop`（回合后上下文落为持久注记）。每次调用都有审计
  （`HOOK_INVOKED`/`HOOK_RESULT`）；无法运行的钩子不阻断任何操作。

## 门禁

```bash
bash scripts/check.sh   # no-stdout + ESLint + vitest + Maven 全量 verify
```

每次提交应过这些门禁；CI 跑同一组（加 best-effort 实测探针）。
