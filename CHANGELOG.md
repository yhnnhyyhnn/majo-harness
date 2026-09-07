# Changelog

All notable changes to majo-harness. Entry format: feature summary (English)
with 中文说明 where useful. Dates are best-effort (repo history is the
source of truth).

## [0.1.0] - 2026-09-06

### Dependency
- **jcordis 1.0.1-SNAPSHOT** (local repo): adopted agent-scope semantics —
  per-agent runs mount on an isolated child context via a plugin fiber
  (shadow registration + rollback), vendored libs/updated; waterfall
  `next()` is now single-use with defensive arg copies, so pre-execute
  rewriting was removed (observe/reject only).

First tagged release (git `v0.1.0`): full capability seams, web-parity UI, three-tier plugin model, and the agent-scoping milestone M-C1…C3 verified against a real model.

### Plugins & web serving
- **Three-tier plugin model** (后端 SPI / 托管页面 + postMessage 桥 / 原生
  `plugin.mjs` 运行时进槽)：`majo-web --plugin name=jar` hosts
  `static-web/<name>/` assets under `/plugins/<name>/…`, lists them via
  `GET /api/plugins` (title from `plugin.json`, module URL when present); the
  sidebar **Plugins** section opens hosted pages in a sandboxed pane and
  loads native modules (`register(host)` + rollback disposers through the
  runtime registrar). Example: `examples/web-plugin-demo` +
  `scripts/build-plugin-demo.sh`; guide:
  `docs/plugin-development(.zh-CN).md`. 插件页面可经 postMessage 驱动宿主
  （flash/newChat/close/sendTask/openSession）。原生模块可在 Plugins 面板
  **mount / reload（cache-bust 热重载 plugin.mjs）/ unload（disposer 回滚槽贡献）**；
  `.mjs` 按 `text/javascript` 伺服。
- **Web serving reliability**: JDK `HttpServer` serializes requests per
  keep-alive connection, which could hang Chrome's parallel module fetches —
  `WebMain` sends `Connection: close` on static/JSON responses (SSE stays
  open). `web-ui` vite dev proxies `/api` + `/plugins` to a backend
  (`MAJO_API_TARGET`).
- **UI boot diagnostics**: `#root` fallback marker + window-error /
  `onRecoverableError` surfacing written straight into the page.

### Capability / data plumbing
- `ToolResult` structured `data` seam: shell/command `{exitCode,stdout,
  stderr}`, `read_file {path}`, `web_search {hits}`, `web_fetch {url,title}`,
  `delegate_task {childSessionId}` — durably logged (`FIELD_DATA`), on the
  wire as `EventFrame.data`, rendered as rich tool cards; child links jump to
  the delegated transcript.
- Real no-key search backend `web-search-wiki` (lazy-mount); sample skills in
  repo `skills/` mounted by web profiles; subagent recent-run log surfaced
  (`GET /api/subagents`); per-session model override, `sinceSeq` incremental
  events (`GET …/events?since=`), durable web state under
  `~/.majo-harness/web/`.
- **Verified on a real model (kilo free tier):** a fan-out prompt produced two delegate_task calls in one tool round, children ran in parallel, per-child calc approvals surfaced over SSE tagged `subagent-<child8>`, and the parent assembled correct results. Scoped child agents (M-C1 seed): `AgentSpec` + `SubagentService.delegateSpec` runs a child turn on an isolated context subtree (fresh loop instance via a lightweight plugin, registration rolled back by the plugin fiber) with per-spec model/system prompt/max-steps; parallel `delegate_task` fan-out (`parallelDelegates`, on in web/web-mock profiles). 设计文档见 `docs/agent-context(.zh-CN).md`。
- Agent-scoped interaction routing (M-C2/C3): approvals/questions carry the originating agent label (SSE frames + rail tags); per-scope auto-approve and an allowed-tools whitelist (backstop on the PRE_EXECUTE chain) enforce spec policy.

### Web UI (dsh-style dark theme)
- Design tokens ported from deepseek-harness `ui-theme`; full session
  management: rename/delete, full-text search (highlight + keyboard + jump &
  flash), multi-select manage mode (batch delete/archive), Active/Archived
  views, JSONL export/import; message timestamps, 👍/👎 feedback, copy;
  slash-command grouped completions; mobile drawer sidebar, safe areas and
  touch targets.

### Earlier milestones (summary)
- M0–M3 capability seams: sessions (event JSONL + projections), tools, LLM
  streaming + providers, agent-loop, fs/subprocess/shell/sandbox,
  interactions (approval/ask-user), skills, subagents, settings/credentials,
  titles.
- Typed wire contract: `WebApiModels` DTOs + `SessionEventType` →
  `WebTypesGenerator` → `web-ui/src/types.ts`; backend serializes the same
  records.
- Entries: `majo` CLI one-shot, headless demo, `majo-web` app; external plugin
  jars via SPI (`--plugin`, `loadPluginJar`, hot `replaceJar`).

- **CI (roadmap E1) green on ubuntu** (`mvn clean verify` + vitest + plugin demo best-effort; jcordis-all/parent vendored under `lib/`); **frontend vitest suite (E2)** shipped (9 tests).

## [Unreleased]

- **Demo depth (roadmap D2)**: offline `web_fetch`/`web_search` tool cards in
  web-mock via a new `local-file` fetch backend (`LocalFileFetchProvider`, root
  `examples/demo-corpus`, traversal-safe) + static search results; mock cue
  `fetch <url>` maps to `web_fetch`, answer prefix becomes `page: `. Verified
  in a real browser: fetch / search / shell cards all offline.

- **Agent M-C4**: host-policy plugin islands (`SubagentService.registerIsland` + `islands:` on `delegate_task`/REST) mount inside scoped runs and roll back with the scope. M-C1…C4 complete on jcordis 1.0.1-SNAPSHOT (no 1.1 wait).

Working notes for the next iteration (see docs/agent-context.md for the completed milestone).
