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

## 20 个工具

| 工具 | 功能 |
|---|---|
| `calc` | 整数运算（门控→审批卡） |
| `read_file` | 读文件（门控） |
| `run_shell` / `run_command` | Shell/命令执行（门控） |
| `run_background` | 后台执行 + `job_output/list/kill` |
| `web_search` / `web_fetch` | 网页搜索与抓取 |
| `delegate_task` | 扇出 scoped 子代理 |
| `todo_write` | 替换会话待办列表 |
| `exit_plan_mode` | 提交计划等审批 |
| `schedule_create/list/delete` | 定时提醒 |
| `run_code` | **PTC**：在 Node.js 进程执行 JavaScript |
| `spill_read` | 取回外置存储的超长工具输出 |
| `workflow_run` | 执行命名工作流 |
| `list_skills` / `load_skill` | 加载技能指令 |

## 关键功能

### @file 提及

在 composer 里输入 `@` 触发工作区文件补全。选中后文件内容注入为持久
上下文注记——模型无需工具往返即可读到。纯文本（二进制拒绝），64k 字符。

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

- 敏感工具（`calc`、`read_file`、`run_shell`、`run_command`、
  `web_search`、`web_fetch`、`workflow_run`）**默认门控**——模型调用时
  你看到审批卡，Allow/Deny。
- 会话级策略：`session.approval.<tool-id>` = `ask|never|auto`。
- 审批审计对（`APPROVAL_REQUESTED`/`APPROVAL_DECIDED`）为每次门控调用
  持久写入会话日志。
- MCP env/header 值只引用环境变量**名**——凭证永不进入 profile 文件。
- MCP stdio 子进程获得脱敏环境（仅白名单项）。

## 门禁

```bash
bash scripts/check.sh   # no-stdout + ESLint + vitest + Maven 全量 verify
```

每次提交应过这些门禁；CI 跑同一组（加 best-effort 实测探针）。
