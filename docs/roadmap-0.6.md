# Roadmap 0.6 (audit Round 2 alignment: fs write family, usage metering, cancellation, cron)

Round 2 of the dsh audit (`docs/audit-dsh-2026-09.md`, 2026-09-30, against
dsh 0.2.0-rc.1) ranked the remaining alignment debt by practical value. This
cycle lands the four well-scoped priorities; deeper themes stay explicit.
Status lives in the CHANGELOG (`## [Unreleased]`).

## P1 — fs write family (highest value, lowest cost)

- **`write_file`** ✅: create/overwrite a UTF-8 file (parents created).
- **`edit_file`** ✅: exact-string replace with required uniqueness
  (`replace_all` opt-in) — composed on the provider's read/write, so the SSH
  world gets it for free.
- **`glob`** ✅: recursive `**`-aware pattern search under a root (local
  provider upgraded from shallow listing; SSH provider stays `find`-based).
- **`grep`** ✅: regex content search with include-glob filter and head
  limit — `FsProvider.grepText` seam method (local: `Files.walk`; SSH:
  remote `grep -rn`).
- Naming keeps the majo family convention (`read_file`/`write_file`/
  `edit_file`) with dsh-exact `glob`/`grep`; the read-name divergence stays
  documented.

## P2 — token usage metering (minimal LLM vocabulary slice)

- **`TokenUsage`** ✅: input/output (+cacheRead/cacheWrite when the provider
  reports them) on `ChatResponse`, nullable; the 2-arg constructor shape is
  preserved for existing callers.
- **OpenAI-compatible parsing** ✅: non-stream `usage` object and the final
  stream chunk's `usage` both map onto it.
- **Durable logging** ✅: ASSISTANT_MESSAGE events carry the usage fields of
  their round when known — usage becomes observable per request without any
  estimator change.
- Content blocks (Text/Reasoning/Image/File) and the StreamChunk protocol
  are **deferred**: with no multimodal provider on the roadmap they are dead
  weight; this slice keeps the door open at zero API cost.

## P3 — minimal cancellation

- **Typed TURN_END** ✅: durable `reason` field — `completed` (normal), 
  `error` (turn failed; previously the turn was left open), `aborted`,
  `max_steps`.
- **`abort(sessionId)`** ✅: cooperative cancel — the step loop checks the
  flag at step boundaries and before tool dispatch, closes the turn
  `aborted`, and returns the answer-so-far.
- **HTTP surface** ✅: `POST /api/sessions/{id}/abort`.
- Documented limitation: no mid-request/mid-tool interruption (dsh
  AbortController parity stays future work); pending inbox still converges
  after an aborted turn.

## P4 — cron schedules

- **Vixie 5-field parser** ✅ (`CronExpression`): minute hour dom month dow,
  with `*`, lists, ranges, steps; explicit IANA `timezone` support; 6-field
  input rejected with a clear error (dsh parity).
- **`schedule_create`/`schedule_update` gain `cron` + `timezone`** ✅:
  stored durably (new event fields), next occurrence computed at
  create/update and re-computed after each fire and on restart rescan.

## P5 — hooks compatibility bridge (next candidate)

Run Claude Code / Codex `hooks.json` command hooks on the waterfall surface
(`PreToolUse` blocking + model-visible messages, context injection). Small,
practical, reuses existing fixtures.

## P6 — skill and PTC deepening (candidates)

- Skill: scope chain (project/custom/user) + priority rank + single `skill`
  tool with a persistent catalog message.
- PTC: host-tool callback protocol from `run_code` programs (fd channel +
  nested dispatch + per-call dispatch log).

## P7 — goal system (design study first)

Event-sourced goal domain + auto-continue driver + goal tools. Attractive
mechanically but a cycle of its own; needs a design proposal (like workflow
v1) before any code.

## Explicitly out of scope for 0.6

The host product layer — storage/workspace/session-query/preset/desktop,
webhook/feedback/identity — conflicts with majo's single-user-local
positioning at this scale. Multimodal content blocks, retry executor, and
mid-request cancellation also stay out. Session format v2/v3/v4 remains a
recorded divergence (the migration-chain mechanism exists for when it is
needed).
