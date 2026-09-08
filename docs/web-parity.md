# Web parity with deepseek-harness

English | [中文](web-parity.zh-CN.md)

The reference client (`packages/client`) breaks the UI into capability modules
(`ui-chat`, `ui-session`, `ui-tool`, `ui-approval`, `ui-user-questions`,
`ui-model-selection`, `ui-commands`, `ui-settings`, `ui-plan`, `ui-goal`,
`ui-jobs`, `ui-sidebar`, `ui-theme`, …). This page tracks how far
`majo-web` (the React/Vite app under `web-ui/`) has reached toward that
feature surface, and what each remaining feature needs from the Java backend.

Legend: ✅ shipped · 🟡 partial · ⬜ not yet

## Conversation core

| dsh feature | majo | Notes |
|---|---|---|
| Session sidebar (list/new/titles) | ✅ | newest-first; event counts; heuristic titles + user rename (✎) and delete (✕) per row |
| Conversation transcript (user/assistant) | ✅ | full re-render per turn (log is the source of truth) |
| Tool call + result rendering | ✅ | chips with JSON args, ok/error dots |
| Turn/request metadata | 🟡 | REQUEST_HEADER meta line (model, tool list) |
| Streaming token display | ✅ | SSE `/api/turn/stream`: chunk frames feed the live bubble |
| Incremental append (no full re-render) | ✅ | each log/chunk frame pushes one event; stream closes on done |
| Message copy / feedback (👍👎) | ✅ | assistant text + tool cards ⧉ copy; user bubbles copy; 👍👎 persisted per durable seq (`/api/messages/…/feedback`) |
| Markdown/code rendering in answers | ⬜ | `ui-renderer`; code blocks + links + lists exist; tables/language tags pending |
| Ask-user inline question bubble | ✅ | rail in approval/ask features (`ctx.interactions` queue → SSE) |
| Approval prompt UI | ✅ | rail with allow/reject (`ctx.interactions` + `tool-approval`) |

## Session & configuration

| dsh feature | majo | Notes |
|---|---|---|
| Rename / delete session | ✅ | `PUT/DELETE /api/sessions/:id` (+ `/title`); memory & file stores; active-session fallback |
| Session search | ✅ | `/api/search?q=` full text over durable events; debounced sidebar box, hit highlighting, ↑↓/Enter/Esc, jumps & flashes the located message |
| Session manage / archive | ✅ | multi-select batch delete (`deleteSessions`) + batch archive; Active/Archived views (`?view=active\|archived\|all`), restore inline in search hits |
| Export / import sessions | ✅ | `GET …/export` downloads replayable NDJSON; `POST /api/sessions/import` replays it into a fresh session (strict seq, rollback on failure) |
| Model selection control | ✅ | header selects: global (`GET/PUT /api/settings/model`) + per-session override (`PUT/DELETE /api/sessions/:id/model`); `REQUEST_HEADER` logs the actual model |
| Slash commands (`ui-commands`) | ✅ | commands slot: `/help /clear /new /model /session-model /status /delegate` with live grouped completions (↑↓/Tab/Enter/Esc); backend host commands (`GET /api/commands`) without a client twin auto-register in a `host` group; features extend via `addCommand` |
| Settings (general/models/plugins) | ✅ | sidebar Settings section: version/models/tools/skills facts (`/api/info`) |
| Mobile layout | ✅ | ≤860px: slide-in sidebar drawer (☰/backdrop), enlarged touch targets, safe-area padding, 16px composer font, landscape tweaks |
| Theme switching | ⬜ | trivial CSS once colors are variables (single dark theme today, dsh tokens) |
| Web/ACP connectivity & reconnect banner | ✅ | offline banner + retry |

## Capability panels (tied to backend seams)

| dsh feature | majo seam status | UI |
|---|---|---|
| Plan mode (`ui-plan`) | backend plan not built | ⬜ |
| Goals (`ui-goal`) | backend goals not built | ⬜ |
| Jobs (`ui-jobs`) | backend jobs not built | ⬜ |
| Schedule (`ui-schedule`) | backend not built | ⬜ |
| Workflow run (`ui-workflow-run`) | backend not built | ⬜ |
| Subagent activity (`ui-subagent`) | backend subagent exists | ✅ sidebar section (recent delegations, polls) + parent→child transcript link from delegate cards; delegation supports scoped model/maxSteps/autoApprove/allowedTools + host islands + per-agent `settings` overrides |
| Skills panel (`ui-skill`) | backend skills exists | ✅ sidebar section (`/api/skills`, polls) |
| Plugins management | three-tier plugin model + jar hot reload | ✅ sidebar section: hosted pages + native `plugin.mjs` mount/reload/unload, jar mtime auto-reload, version/slots chips, duplicate-id & unversioned warnings |
| Agent team (`ui-agent-team`, experimental) | backend not built | ⬜ |
| Trajectory (`ui-trajectory`) | backend session log has everything | 🟡 via transcript |

## Wire contract & panels plumbing

- Typed single source of truth: `WebApiModels` DTO records + `SessionEventType` enum → `WebTypesGenerator` → `web-ui/src/types.ts`; the backend serializes the same DTOs (`NON_NULL` for `@OptionalWire`).
- Endpoints: `GET/POST /api/sessions` (`GET ?view=active|archived|all`), `GET/PUT/DELETE /api/sessions/:id` (`PUT …/title`, `PUT/DELETE …/model`, `PUT/DELETE …/archive`, `GET …/events?since=`, `GET …/feedback`, `GET …/export`), `POST /api/sessions/import`, `GET /api/search?q=`, `GET/PUT /api/settings/model`, `GET /api/skills`, `GET /api/subagents` (+ `POST /api/subagents/delegate`), `GET /api/plugins` (+ `POST …/:name/reload`, `DELETE …/:name`), `GET /api/commands` (+ `POST /api/commands/:name`), `GET /api/health`, `GET /api/metrics`, `GET /api/info`, `GET /api/openapi.json`, approvals/questions decisions, `PUT/DELETE /api/messages/:id/:seq/feedback`, SSE `/api/turn/stream` (per-stream `X-Turn-Id` + heartbeat).
- Tool results carry optional structured `data` on the wire (exit codes, hits, child session ids…) so cards render without re-parsing text; text stays the model-visible truth.
- UI assembly stays registration-only: `features/*` modules fill message-renderer/rail/sidebar/command slots through `FEATURES` (compile-time list); shell code only renders slots and injects runtime seats (`openSession`, `rate`, command `run`).

## Search/fetch backends (server-side web family)

- Seams: `SearchProvider`/`FetchProvider` register on `ctx.web`; first usable provider (or explicit id) serves `web_search`/`web_fetch`; missing backend fails structured; provider text is external/untrusted.
- Shipped: `web-fetch-http` (anonymous HTML→text), `web-search-duckduckgo` (no-key DuckDuckGo HTML, lazy), `web-search-static` (offline canned), `web-search-wiki` (real no-key Wikipedia API, lazy), `web-fetch-local` (offline corpus under `examples/demo-corpus`, traversal-safe — powers the fully-offline tool-card demos in `web-mock.yml`).

## Recommended order

> Completed for 0.1.0 (2026-09): the roadmap items below are historical; the status tables above reflect the shipped state. / 0.1.0 已完成（2026-09），下表为历史路线；上方状态表为准。

1. **Real-model conversation polish** — streaming + incremental append
   (SSE `/api/turn`), markdown-lite renderer, copy message. Backend work:
   a chunked/SSE path in `WebMain`; everything else is front-end only.
2. **Model picker** — `GET/PUT /api/settings/model` backed by `ctx.settings`
   + per-session override stored as a durable event.
3. **Approval & ask-user in the UI** — add `ctx.interactions` queue-driven
   channel (server events → browser), `ui-approval`/`ui-user-questions`
   equivalents, powered by the seams already shipped.
4. **Capability panels as the backend seams land** (plan/goals/jobs/subagent/
   skills…).
