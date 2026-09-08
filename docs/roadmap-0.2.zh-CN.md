# Roadmap 0.2（候选议题）

下一发布周期的备选工作清单，按主题分组并标注粗略投入/收益；按迭代挑取。
进度记录在 CHANGELOG（`## [Unreleased]`）。

> **状态（2026-09，v0.1.1）**：以下条目基本全部落地或被取代——A1 成为 jcordis
> agent-fiber 语义、A2 落地为 per-agent settings 作用域、B1/C1–C3/D2/E1/E2
> 均已交付；原 D1 拆为这里的 DuckDuckGo 后端 + 需联网主机的活体验证。唯一未做
> 的是 **E3 真机移动端 QA**（手工项，需实体手机）。

## A. agent 上下文纵深（M-C1…C3 后续）

- **A1 · jcordis 子上下文/独立 fiber 实验**（中投入，使能项）。当前 harness 侧
  作用域依赖 `Context.extend()` 子级上插件自身 fiber 的销毁语义；要做到“每代
  理插件孤岛”（M-C4），需先在 jcordis 层补**每子代 fiber 干净拆除 + 配置隔离**
  原语。交付：小型 SPI 实验与提案，而非生产代码。
- **A2 · 按代理的 settings/凭据/title 作用域**（小–中）：代理默认继承根值，但可在
  其作用域内覆盖 settings 键；沿用 InteractionContext 式线程局部或 scope
  intercept 验证隔离。
- **A3 · Web SSE 审批 UX**（小）：rail 上“n 个委派待批”折叠摘要、按代理展开，
  取代每个子代理一张卡。

## B. 命令与控制面

- **B1 · 后端命令注册表**（中）：目前斜杠命令在客户端；服务端 `ctx.commands`
  接缝可让插件以 harness 上下文贡献命令（并与客户端命令镜像）。
- **B2 · `/delegate` UI 便捷化**（小）：composer/小表单里选 model/spec 字段；
  REST 委派后自动打开子转写。

## C. 插件生态

- **C1 · 插件模板仓库/脚手架**（小–中）：starter（后端 SPI + `static-web` 页 +
  原生 `plugin.mjs`），可发布为 GitHub template。
- **C2 · 版本化插件清单**（小）：`plugin.json` 增加 `version`/`slots`；
  `GET /api/plugins` 上报，UI 可对重复 id/陈旧 reload 告警。
- **C3 · 插件 jar 监视器**（中）：目录轮询；jar 变化时通知浏览器 reload 原生
  模块（cache-bust 已可用）。

## D. Provider 与离线演示

- **D1 · 更多真实 SearchProvider 后端**（中）：如 DuckDuckGo Lite HTML 或
  尽量无 key 的 SerpAPI 形态；在线检查需网络（离线解析测试仍覆盖映射）。
- **D2 · web-mock 里 fetch 到文件/shell 演示轮**（小）：经 cue mock 挂 fs 工具卡，
  让工具卡可全离线演示。

## E. 工程卫生

- **E1 · CI ✅**（小）：GitHub Actions 跑 `mvn clean verify` + 插件 demo + vitest——ubuntu 全绿（jcordis-all/parent 已 vendor 到 lib/） `mvn clean verify` +
  `scripts/build-plugin-demo.sh`；CI 有浏览器运行时后再加无头冒烟。
- **E2 · 前端单测 ✅**（中）：vitest 覆盖 chat/搜索块、markdown 表格、命令补全辅助（9 用例）、搜索/键盘辅助、命令补全
  分组——无需浏览器。
- **E3 · 真机移动端走查**（小）：真机一轮 QA（safe-area、触控目标、抽屉）并修复
  暴露问题。

## 建议顺序

1. E1/E2（卫生，解锁后续）。
2. A1（使能）+ 并行 B1（互相独立）。
3. C1/C2（生态），再补 D1/D2 演示广度。
4. A2/A3、C3 按 UX 深度推进。

0.2 验收：以上合并后始终 `mvn clean verify` 全绿，并保持验证清单
（docs/verification.zh-CN.md）同步。
