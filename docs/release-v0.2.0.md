# majo-harness v0.2.0 release notes (2026-09-17)

Third tagged release and the first cut with a release pipeline
(`scripts/release.sh`: full gates → CHANGELOG finalize → lockstep version
stamp → release-version verify → jar version assertion → tag + push).
Everything below keeps `scripts/check.sh` green (no-stdout gate, ESLint,
vitest, full Maven verify across 26 modules). Full item history:
`CHANGELOG.md`.

## Highlights

- **Architecture alignment with deepseek-harness (Phase 1)**: the agent loop
  grew a **dual inbox** — `followup` (queues a next turn; idle sessions are
  woken by a virtual-thread driver, busy ones converge inline),
  `steer` (splices durable user input at the next step boundary) and
  `inject` (a durable `CONTEXT_NOTE` that never wakes the loop). Approvals
  became **durable audit pairs** (`APPROVAL_REQUESTED`/`APPROVAL_DECIDED`,
  wrapped by the open turn) with a session-level **ask/never/auto** policy.
  The **"model-visible means logged" invariant** is pinned by a test that
  rebuilds every model request from the log at ask time.
- **Feature surface parity (Phase 2)**: five new modules in the standard
  Service + Plugin + tool pattern —
  **plan mode** (`/plan` command, `exit_plan_mode` review through the
  interaction seam, composer chip), **todo** (`todo_write` whole-list
  replacement + TodoPanel), **jobs** (`run_background`,
  `job_output`/`job_list`/`job_kill`, completion notices ride the inbox),
  **schedule** (`schedule_create`/`list`/`delete`, restart-safe timers,
  delivery as follow-up turns) and **compaction** (pressure-triggered +
  manual `/compact` summarization persisted as `CONTEXT_COMPACTION`,
  derivation restarts from the summary, Context Meter in the header).
- **UI experience (Phase 2-D)**: a Chat/Trajectory view ring — the
  trajectory is a turn-grouped ledger of every durable event with durations
  and a text filter — and dark/light/system theme switching (the stylesheet
  was already dsh-token based; light is a variable re-aliasing block).
- **Engineering foundation (Phase 0)**: the 1715-line `WebMain` and 783-line
  `App.tsx` god classes split into focused units; service code logs via
  slf4j (gated by `scripts/verify-no-stdout.sh`); constant-time token
  comparison; the version string comes from the build; ESLint 9 flat config
  with zero warnings.
- **Test infrastructure (Phase 1 + 3)**: LLM **record/replay** fixtures
  (`RecordingChatModel`/`ReplayChatModel` with request-drift detection), a
  generative **tool catalog** (`docs/tool-catalog.md`, verified against the
  live registry in the build), a versioned per-session **search cache**
  replacing the O(N) per-keystroke scan, and CI gates aligned with the local
  `scripts/check.sh` entry.

## Upgrading from v0.1.1

- Pull, `mvn clean verify`, run `java -jar majo-web-0.2.0.jar` — sessions,
  settings, titles and feedback under `~/.majo-harness/web/` carry over.
- New optional profile rows: `todo`, `plan`, `jobs`, `schedule`,
  `compaction` (all mounted by the shipped `web.yml`/`web-mock.yml`).
- New endpoints: `/api/sessions/{id}/todos|plan|jobs|schedules|context`.
- Session logs gain new event kinds (`CONTEXT_NOTE`, `APPROVAL_*`,
  `TODO_SET`, `PLAN_SET`, `SCHEDULE_SET`, `CONTEXT_COMPACTION`); existing
  logs load unchanged — old kinds stay valid, new kinds only appear as the
  features are used.
