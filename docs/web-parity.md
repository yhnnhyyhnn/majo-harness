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
| Message copy / feedback (👍👎) | ⬜ | `ui-message-feedback`; backend feedback seam needed |
| Markdown/code rendering in answers | ⬜ | `ui-renderer`; add a small safe renderer |
| Ask-user inline question bubble | ✅ | rail in approval/ask features (`ctx.interactions` queue → SSE) |
| Approval prompt UI | ✅ | rail with allow/reject (`ctx.interactions` + `tool-approval`) |

## Session & configuration

| dsh feature | majo | Notes |
|---|---|---|
| Rename / delete session | ✅ | `PUT/DELETE /api/sessions/:id` (+ `/title`); memory & file stores; active-session fallback |
| Model selection control | ✅ | header `<select>` over `GET/PUT /api/settings/model` (persisted via `ctx.settings`) |
| Slash commands (`ui-commands`) | ⬜ | backend has no commands seam yet |
| Settings (general/models/plugins) | ✅ | sidebar Settings section: version/models/tools/skills facts (`/api/info`) |
| Theme switching | ⬜ | trivial CSS once colors are variables |
| Web/ACP connectivity & reconnect banner | ✅ | offline banner + retry |

## Capability panels (tied to backend seams)

| dsh feature | majo seam status | UI |
|---|---|---|
| Plan mode (`ui-plan`) | backend plan not built | ⬜ |
| Goals (`ui-goal`) | backend goals not built | ⬜ |
| Jobs (`ui-jobs`) | backend jobs not built | ⬜ |
| Schedule (`ui-schedule`) | backend not built | ⬜ |
| Workflow run (`ui-workflow-run`) | backend not built | ⬜ |
| Subagent activity (`ui-subagent`) | backend subagent exists | ✅ sidebar section (recent delegations, polls) |
| Skills panel (`ui-skill`) | backend skills exists | ✅ sidebar section (`/api/skills`, polls) |
| Agent team (`ui-agent-team`, experimental) | backend not built | ⬜ |
| Trajectory (`ui-trajectory`) | backend session log has everything | 🟡 via transcript |

## Wire contract & panels plumbing

- Typed single source of truth: `WebApiModels` DTO records + `SessionEventType` enum → `WebTypesGenerator` → `web-ui/src/types.ts`; the backend serializes the same DTOs (`NON_NULL` for `@OptionalWire`).
- Endpoints: `GET/POST /api/sessions`, `GET/PUT/DELETE /api/sessions/:id` (`PUT …/title`), `GET/PUT /api/settings/model`, `GET /api/skills`, `GET /api/subagents`, `GET /api/info`, approvals/questions decisions, SSE `/api/turn/stream`.
- UI assembly stays registration-only: `features/*` modules fill message-renderer/rail/sidebar slots through `FEATURES` (compile-time list); shell code only renders slots.

## Search/fetch backends (server-side web family)

- Seams: `SearchProvider`/`FetchProvider` register on `ctx.web`; first usable provider (or explicit id) serves `web_search`/`web_fetch`; missing backend fails structured; provider text is external/untrusted.
- Shipped: `web-fetch-http` (anonymous HTML→text), `web-search-static` (offline), `web-search-wiki` (real no-key Wikipedia API, lazy-mount, `web.yml`).

## Recommended order

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
