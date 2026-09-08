# majo-harness v0.1.1 release notes (2026-09-08)

Second tagged release. Everything below keeps `mvn clean verify` green and
the web/soak suites passing; CI (GitHub Actions, ubuntu + bubblewrap + vitest)
is green on the Maven-Central build. Full item history: `CHANGELOG.md`.

## Highlights

- **jcordis 1.0.1 from Maven Central** (`io.github.yhnnhyyhnn`): the framework
  is now a normal central dependency (`jcordis-core` + `jcordis-loader`); the
  local 1.0.1-SNAPSHOT and vendored `lib/` are gone, and CI has no
  install-file step. Same `io.jcordis.*` imports, no code changes.
- **Agent milestone M-C1…C4 complete**: scoped per-agent runs (jcordis agent
  fiber semantics), per-agent interaction routing + auto-approve, allowed-tool
  whitelists, full tool-spec delegation, and **host-policy plugin islands** —
  `registerIsland(name, …)` mounts plugins inside a child scope that roll back
  when the turn ends; `delegate_task` / REST expose them by name only (the
  model never supplies code).
- **Parallel turns for real**: turns lock per session, not globally; approvals
  and questions route through a per-stream notifier (verified in a real
  browser with two tabs — each stream keeps its own `X-Turn-Id` and decisions
  never cross streams).
- **System hardening all in**: bearer/`?token=` API auth + secret redaction,
  `/api/health`, host builtin backend commands (`status`/`delegate`) surfaced
  in the web UI, turn ids on SSE, loopback-only bind by default
  (`--host` opt-in), OpenAPI descriptor endpoint + a **drift guard test**,
  atomically-written settings with retry, and empty HTTP responses fixed for
  the JDK HttpClient (they now close the body stream properly).
- **Soak-grade verification**: HTTP-level soak suite (24 parallel
  cross-session turns vs a serialized control group, same-session bursts with
  concurrent deletes, 6 hot jar reloads during active turns, SSE-drop
  resilience + 10s heartbeat, client-abort vs server-error accounting).
  The soak found and fixed a real bottleneck: `FileSessionStore` now locks per
  session file instead of globally.
- **Offline demo depth (D2)**: a local-file fetch backend serves
  `examples/demo-corpus` with zero network; fetch/search/shell/file tool
  cards are all demonstrable in `web-mock`, browser-verified.
- **DuckDuckGo key-less search** (`web-search-duckduckgo`) joins the static and
  Wikipedia backends; parsing is pure and offline-tested.

## Notes / caveats

- The DuckDuckGo backend needs an internet connection for live calls (this
  dev box had none); parsing is covered by offline unit tests.
- Real-device mobile QA (E3) is deferred; the web UI is tested in a narrow
  DevTools viewport only.
- jcordis lives at `io.github.yhnnhyyhnn` — the old `io.jcordis:jcordis-all`
  aggregator is not published; use core/loader.

---

# majo-harness v0.1.1 发布说明（2026-09-08）

第二个打标版本（`v0.1.0` → `v0.1.1`）。以下内容保持 `mvn clean verify` 全绿；
CI（GitHub Actions / ubuntu / bubblewrap / vitest）在「Maven Central 依赖」构建下
通过。完整条目见 `CHANGELOG.md`。

## 亮点

- **jcordis 1.0.1 走 Maven Central**（`io.github.yhnnhyyhnn`）：框架改为普通中央
  依赖（`jcordis-core` + `jcordis-loader`）；本地 1.0.1-SNAPSHOT 与 vendored
  `lib/` 移除，CI 不再需要 install-file。`io.jcordis.*` import 不变、零代码改动。
- **Agent 里程碑 M-C1…C4 完成**：scoped per-agent 运行（jcordis agent fiber 语义）、
  per-agent 交互路由 + auto-approve、allowedTools 白名单、全 spec 委派，以及
  **宿主策略插件孤岛**——`registerIsland(name, …)` 在 child 作用域挂插件、turn 结束
  随作用域回滚；`delegate_task` / REST 只按名暴露（模型永不提供代码）。
- **并行 turn 落地**：turn 按会话加锁而非全局；审批/问答经 per-stream notifier
  路由（真实浏览器双标签验证——每流独立 `X-Turn-Id`，决策不串流）。
- **系统侧加固全部就位**：Bearer/`?token=` API 鉴权 + 密钥脱敏、`/api/health`、
  宿主内置后端命令（`status`/`delegate`）进入 Web UI、SSE 带 turnId、默认只绑回环
  （`--host` 可选）、OpenAPI 描述端点 + **漂移防护测试**、设置原子写 + 重试、
  修复空响应体对 JDK HttpClient 不终结的问题。
- **Soak 级验证**：HTTP 级并发套件（24 并行跨会话 turn 对串行对照组、同会话突发 +
  并发删除、活跃 turn 中 6 次 jar 热 reload、SSE 断流韧性 + 10s 心跳、客户端中断
  与服务端错误分开计数）。soak 揪出并修复真瓶颈：`FileSessionStore` 由全局锁改为
  按会话文件锁。
- **离线演示深度（D2）**：local-file fetch 后端零网络伺服 `examples/demo-corpus`；
  fetch/search/shell/file 工具卡在 `web-mock` 全部离线可演示（浏览器实测）。
- **DuckDuckGo 免 key 搜索**（`web-search-duckduckgo`）加入 static/Wikipedia 后端；
  解析纯函数化并有离线单测。

## 备注

- DuckDuckGo 后端实机查询需要联网（本机无外网）；解析层由离线单测覆盖。
- 真机移动端 QA（E3）暂缓；Web UI 仅在 DevTools 窄视口测试。
- jcordis 现位于 `io.github.yhnnhyyhnn`——旧的 `io.jcordis:jcordis-all` 聚合器
  未发布，请使用 core/loader。
