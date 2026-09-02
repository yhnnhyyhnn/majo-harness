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
| Session sidebar (list/new/titles) | ✅ | newest-first; event counts; heuristic titles |
| Conversation transcript (user/assistant) | ✅ | full re-render per turn (log is the source of truth) |
| Tool call + result rendering | ✅ | chips with JSON args, ok/error dots |
| Turn/request metadata | 🟡 | REQUEST_HEADER meta line (model, tool list) |
| Streaming token display | ⬜ | needs SSE / chunked turn API |
| Incremental append (no full re-render) | ⬜ | needs `sinceSeq` param or stream |
| Message copy / feedback (👍👎) | ⬜ | `ui-message-feedback`; backend feedback seam needed |
| Markdown/code rendering in answers | ⬜ | `ui-renderer`; add a small safe renderer |
| Ask-user inline question bubble | ⬜ | `ui-user-questions`; backend `ctx.interactions.ask` exists — needs an in-turn channel |
| Approval prompt UI | ⬜ | `ui-approval`; backend `ctx.interactions` + `tool-approval` exist — needs a queue+UI channel |

## Session & configuration

| dsh feature | majo | Notes |
|---|---|---|
| Rename / delete session | ⬜ | needs DELETE/PATCH endpoints |
| Model selection control | ⬜ | `ui-model-selection`; backend: multiple registered models exist (`ctx.llm`) — needs per-session model setting |
| Slash commands (`ui-commands`) | ⬜ | backend has no commands seam yet |
| Settings (general/models/plugins) | ⬜ | `ui-settings`; `ctx.settings` exists |
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
| Subagent activity (`ui-subagent`) | backend subagent exists | ⬜ |
| Skills panel (`ui-skill`) | backend skills exists | ⬜ |
| Agent team (`ui-agent-team`, experimental) | backend not built | ⬜ |
| Trajectory (`ui-trajectory`) | backend session log has everything | 🟡 via transcript |

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
