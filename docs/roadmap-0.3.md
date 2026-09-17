# Roadmap 0.3 (dsh-informed iteration)

majo-harness is a Java 21 port of the deepseek-harness ("dsh") architecture.
This roadmap sequences the next iterations by what the reference project does
and what majo still lacks, audited against `D:\code\git\deepseek-harness`
(architecture docs + `packages/`) in 2026-09. Status lives in CHANGELOG
(`## [Unreleased]`); Phase 0 is in flight.

Phased order (agreed): engineering foundation first, then architecture
mechanics ported from dsh, then the missing feature surface, then ecosystem
hardening. Each phase is independently shippable.

## Phase 0 — Engineering foundation (in flight)

Debt that makes every later phase cheaper; zero API-contract changes.

- **WebMain split**: the 1715-line god class became assembly (`WebMain`) +
  route table (`Router`) + per-domain handlers (`handler/` package) +
  extracted `Metrics`/`PendingInteractions`/`Http`/`WebContext`/`Version`.
  Guarded by `OpenApiDriftTest` + `ConcurrencySoakTest` (both green).
- **Logging discipline**: slf4j (+ `slf4j-simple` runtime) replaces
  `System.out`/`printStackTrace` in service code; `scripts/verify-no-stdout.sh`
  is the regression gate (product-output channels — CLI, transcript printer,
  `WebTypesGenerator` — are exempt).
- **Security details**: constant-time token comparison
  (`MessageDigest.isEqual`); the `?token=` SSE affordance and its risks are
  documented in the README security model section.
- **Version from the build**: `/api/info` + `/api/health` read the filtered
  `majo/version.properties` resource; no hardcoded copy in code.
- **Frontend split**: `App.tsx` (783 lines) shrank to the shell;
  `components/SessionSidebar` (search + manage + list — one unit because a
  search-hit restore refreshes the archived view), `components/PluginFrame`
  (iframe + postMessage bridge), `components/Composer` (slash-command
  arbitration). `usePollingSection` deduplicates the Plugins/Subagents/Skills
  poll loops. ESLint 9 flat config with zero warnings.
- **Docs debt**: web-parity markdown-table row corrected to ✅ (shipped since
  the GFM table renderer + tests).

## Phase 1 — Architecture alignment (port dsh core mechanics)

The mechanisms behind dsh's quality bar, mapped onto jcordis.

- **Turn/step + dual inbox** (ref `packages/core/agent-loop/src/agent.ts`):
  a turn = 0..n steps and stays the durable commit boundary; add
  `send/followup/steer/inject` entries with a wake latch so queued work
  converges instead of racing the running turn. `majo-agent-loop` keeps its
  per-session serialization; the inbox becomes the single entry point.
- **Approval as durable audit events** (ref
  `packages/interaction/user-approval`): persist the ask/decision pair as
  session events wrapped by the open turn (crash-consistent), plus a
  session-level policy (`ask | never`, fail-closed) instead of only the
  in-memory pending queue with timeout deny.
- **"Model-visible means logged" invariant**: a drift test that rebuilds the
  model request purely from the session log and asserts equality with what
  `REQUEST_HEADER` recorded (ref `docs/architecture.md` §invariants).
- **LLM record/replay test infra** (ref `packages/test-support/llm-replay`):
  a recording `ChatModel` wrapper writes provider streams to fixtures;
  tests replay them offline. A scripted fault mock server (disconnects,
  429/5xx, malformed chunks) comes with it (`llm-mock-server` analog).
- **Session format generations** (ref `packages/session/session-persistence-jsonl`):
  name durable files `session.v1.jsonl`, keep immutable committed
  generations, header-only stat/list, and a one-step migration chain.
- **Gate runner** (ref `scripts/run-gates.ts`): one entry
  (`scripts/check.sh`) that runs the same set locally and in CI — Maven
  verify, vitest, lint, `verify-no-stdout.sh`, type drift.

## Phase 2 — Feature parity (four batches, user-selected order)

New capability modules follow the standard pattern (Service + Plugin + tool
consumers + web API + UI section). dsh references are the behavior spec.

- **Batch A · Plan & todo** (ref `packages/plan/plan-mode`, `packages/todo`):
  `/plan` command + `exit_plan_mode` tool riding the interaction seam (approval
  card: approve plan / keep planning with feedback); plan projection state
  survives resume. `todo_write` full-replace list persisted in the session
  log with a conversation TodoPanel. Modules: `majo-plan`, `majo-todo`.
- **Batch B · Jobs & schedule** (ref `packages/jobs`, `packages/schedule`):
  a `ctx.jobs` seam (stable `<kind>-N` ids, per-owner concurrency cap) with
  `run_in_background` on the shell tool, `job_output/job_list/job_kill` tools
  and a header job list; notices inject on completion (busy → next turn, idle
  → wake). Schedule: `schedule_create/list/delete` with `after_seconds` /
  absolute `at` / `every_seconds ≥ 300`, persisted in the session log,
  delivered to the active root agent as follow-up turns; header catalog
  popover. Modules: `majo-jobs`, `majo-schedule`.
- **Batch C · Context management** (ref `packages/compaction`, `packages/llm/token-meter`):
  pressure-triggered auto-compaction + `/compact` (human command, does not
  burn a model turn), tool-result pruning, image offload as stretch; token
  metering feeding a Context Meter panel. Module: `majo-compaction`.
- **Batch D · UI experience** (ref `packages/client/ui-trajectory`,
  `ui-theme`): Trajectory view — turn/step-grouped event ledger with
  durations, a Chat/Trajectory view ring, search within the ledger; theme
  switching (light/dark/system via CSS variables) + composer font size.

## Phase 3 — Ecosystem deepening

- **Tool catalog, generative** (ref `docs/tool-catalog.md` + verify scripts):
  generate a tool catalog from `ToolRegistry.specs()` at boot/test time and
  fail CI when the doc drifts ("gen + verify" pairing).
- **Credentials by name** (ref `packages/credentials`): profiles reference
  env-var names, never values; the web layer only ever sees
  "set / unset / source".
- **Search indexing**: replace the O(N) `/api/search` full scan with a
  per-session inverted index (or SQLite FTS) behind the same endpoint.
- **Release pipeline** (ref `scripts/release/*`): lockstep version bump,
  topology-ordered publish order, packed-artifact verification.

## Explicitly out of scope for 0.3

Workflow orchestration, agent-team, MCP client, terminal PTY, ACP/SDK remote
surfaces, desktop app — dsh has them, but majo's single-user web/CLI scope
should absorb Phases 0–2 first. Revisit after 0.3.
