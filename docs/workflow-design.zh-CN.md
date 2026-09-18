# Workflow v1 — 设计提案（尚未实现）

dsh 的 workflow 包（`packages/workflow`）是最大的延期项。本文提出一个
务实的 v1 设计，供评审后再动工。以下内容均未实现。

## 动机与非目标

真实任务是多步的：调研 → 起草 → 评审，或把一个任务扇出给多个子代理再
汇总。积木都在（scoped 委派、jobs、inbox），缺的是**可复用、有名字的
编排**。

v1 非目标：通用编程语言（除 `onFailure` 外没有循环/分支）、可视化编辑
器、跨会话协调、workflow 注册表版本化。

## 核心模型

一个 workflow 是有名字的步骤列表，以 YAML 声明在 `workflows/` 目录下
（仓库相对路径，与 `skills/` 一致）：

```yaml
name: review-doc
description: 起草一份文档评审，两个独立评审员
steps:
  - id: fetch
    kind: turn
    prompt: "阅读 {{args.path}}，提取文档的关键论断。"
  - id: review
    kind: delegate
    prompt: "评审以下论断的事实错误：\n{{steps.fetch.output}}"
    model: kilo-free            # 可选的每步覆盖
    allowedTools: [read_file]   # 可选的每步白名单
  - id: merge
    kind: turn
    prompt: "把评审合并为最终结论：\n{{steps.review.output}}"
onFailure: abort                # 或 continue
```

- **`turn` 步骤**在运行会话内执行一个模型回合。
- **`delegate` 步骤**经既有 `delegateSpec` 接缝扇出（scoped model /
  maxSteps / allowedTools / 孤岛），声明 `parallel: true` 的多个
  delegate 兄弟步骤并行。
- **模板**：`{{args.*}}` 与 `{{steps.<id>.output}}` 字符串插值，每步
  执行前解析。无表达式、无条件——缺键 fail-loud。
- **运行在专属子会话中执行**（经既有委派接缝 scoped），用户会话日志
  保持干净；运行结束时向请求会话回投一条汇总消息。

## 状态与可观测性

- 新增持久会话事件 `WORKFLOW_START` / `WORKFLOW_STEP` / `WORKFLOW_END`
  写入请求会话（运行 id、步骤 id、状态、时长）——Trajectory 视图免费
  获得渲染；workflow 事件属簿记（派生时跳过，同 TODO_SET），不变量
  依旧成立。
- 崩溃语义：v1 不可恢复；崩溃的运行以 `WORKFLOW_END(status=failed)`
  收场。恢复是 v2 候选。

## 触发

1. `/workflow <name> [json-args]` —— 宿主命令（模块挂载时注册），命令
   注册表天然提供可发现性。
2. `workflow_run` 工具（名称/描述/JSON-schema 在挂载期从 `workflows/`
   目录生成），让*模型*也能启动 workflow。

## 失败与审批

- 默认 `onFailure: abort`；已完成步骤保留在事件里。
- 需要受限工具的步骤在其会话内走普通审批接缝——v1 不做特殊 workflow
  审批路径。

## 分期

- **Phase A（本提案的实现范围）**：定义解析 + 校验、运行器（turn/
  delegate 步骤、模板、事件）、`/workflow` 命令、`workflow_run` 工具、
  轨迹渲染。
- **Phase B**：并行步骤组、运行列表/`/workflow status`、恢复。

## 待定问题（供评审）

1. 定义用 YAML 还是 JSON（提案：YAML，与 `skills/` 一致）。
2. 子会话执行（提案：是，一律如此）vs 会话内步骤。
3. `workflow_run` 是否默认审批门控（提案：是——它可能扇出高成本工作）。
