# Roadmap 0.4 (hardening + MCP) — COMPLETE

majo-harness is a Java 21 port of the deepseek-harness ("dsh") architecture.
Roadmap 0.3 closed with v0.2.0 (all four phases shipped); this cycle first
hardens what exists, then adds the one big missing ecosystem surface: an MCP
client. Sequenced so each phase ships independently; status lives in the
CHANGELOG (`## [Unreleased]`).

## Phase 1 — Durability & test hardening (no new user-visible surface) — SHIPPED

Debt that compounds if deferred; all three items are independent.

- **Session format generations** ✅ (ref dsh
  `packages/session/session-persistence-jsonl`): durable files named
  `session.v1.jsonl`; committed generations are immutable; header-only
  stat/list (directory listings stop parsing full event logs); a one-step
  migration chain (`v(n)` → `v(n+1)`) that fails loudly on unknown versions.
  The in-memory store is untouched. Acceptance: existing on-disk stores open
  transparently after the rename, export/import and replay are unaffected,
  and a legacy `sessions.jsonl` (unversioned name) migrates in one step.
- **LLM fault-injection mock server** ✅ (ref dsh
  `packages/test-support/llm-mock-server`): a scriptable local server (and
  direct `ChatModel` wrappers) injecting mid-stream disconnects, 429/5xx
  responses, and malformed SSE chunks. Acceptance: loop/provider tests pin
  the failure contract — a killed stream fails the turn loudly, the session
  log stays consistent (no half-committed turns), and retry behavior is
  explicit rather than accidental.
- **Tool-result pruning** ✅ (ref dsh `packages/compaction`): oversized tool
  results are pruned in *derived* history (oldest rounds first) behind a
  visible `[pruned N chars]` placeholder; the durable log is untouched
  ("model-visible means logged" still holds — the placeholder is what the
  model sees and what gets logged as derived context pressure relief).
  Acceptance: `MessageDeriver` tests pin placeholder behavior; context
  pressure drops without a full compaction.

Stretch (only if cheap): composer font size control; image offload stays
deferred.

## Phase 2 — MCP client (the main axis) — SHIPPED

The one dsh-parity ecosystem surface worth building now: an MCP client lets
majo consume the existing MCP server ecosystem instead of hand-writing a
Java plugin per capability. Follows the standard module pattern (Service +
Plugin + `ToolRegistry` bridge).

- **Transport** ✅: stdio first (spawn the MCP server process per profile row);
  HTTP/SSE transports only if a concrete need shows up.
- **Registration** ✅: profile rows name servers (command, args, env — env var
  *names* only, riding credentials-by-name); servers connect at boot and on
  plugin mount; failures are loud but non-fatal to the rest of boot.
- **Tool bridge** ✅: `tools/list` results are bridged into the `ToolRegistry`
  as namespaced tools (`mcp__<server>__<tool>`) with JSON-schema args
  mapping; they appear in `/api/tools` and the generated tool catalog for
  free.
- **Calls & safety** ✅: `tools/call` rides the existing approval seam (the
  session policy `ask|never|auto` applies, fail-closed) with call timeouts;
  results enter the transcript as ordinary tool results.
- **Resources/prompts**: deferred unless they fall out cheaply.
- Acceptance ✅: a test MCP server (the in-repo echo server, spawned as a
  real subprocess) mounts in the suite; its tools are listed, callable,
  error-mapped, and flow through the standard tool registry.

## Explicitly out of scope for 0.4

Workflow orchestration (needs a DSL that should grow from real usage),
agent-team (experimental upstream too), terminal PTY, ACP/SDK remote
surfaces, desktop app. SQLite FTS stays a documented upgrade option behind
the per-session search cache.
