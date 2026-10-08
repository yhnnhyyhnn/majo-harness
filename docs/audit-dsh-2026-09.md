# dsh reference audit — 2026-09-18 (incremental)

Follow-up to the original audit that produced roadmap-0.3 (2026-09
mid-month). The reference moved ~1,700 commits and two releases
(`dsh-v0.1.5-rc.2`, `dsh-v0.1.6-alpha.1`) since then. This file records
what changed, what majo adjusted in response, and what remains as
deliberate divergence or future candidates. Each finding: evidence-backed,
one of **adopted** (code landed), **divergence** (deliberate, keep), or
**candidate** (future work).

## Core mechanisms: unchanged (ports stay aligned)

- **agent-loop / dual inbox**: entry kinds still exactly `followup` /
  `steer` / `inject`; wake-latch and convergence semantics unchanged.
- **approval audit**: still `ask | never` policies + the durable
  asked/decided pair.
- **jobs / schedule**: contracts unchanged.
- **llm-replay / llm-mock-server**: fixture vocabulary unchanged (the
  mock-server fault vocabulary is richer than majo's `FaultLlmServer`
  scripts — candidate).

## Adopted in this cycle

1. **Tool-call timeout** (dsh `guard` timeout-policy, shipped-by-default
   there): `tools` plugin gains `toolTimeoutSeconds`; a hung call now
   returns a clear timed-out error instead of stalling the turn forever.
   Web profiles enable it at 600s; library default is off.
2. **Tool-result pruning shape** (dsh `compaction-tool-result-pruner`):
   pruned results keep a marker line plus **head (4096) / tail (1024)**
   instead of full elision; threshold aligned to dsh's 8192 (`pruneChars`
   default changed 4000 → 8192).
3. **MCP stdio env scrubbing** (dsh `scrubbedParentEnv`): spawned MCP
   servers see only an allowlisted subset of the ambient environment
   (PATH/HOME/system basics) plus the row's explicit `env` — ambient
   credentials can no longer leak into server processes.

## Deliberate divergences (keep)

- **MCP prompts bridged** — the reference does not bridge `prompts/*` at
  all; majo exposes `mcp__<server>__get_prompt`. Superset, kept.
- **MCP resources as per-server read-only tools** — the reference moved to
  three *shared* tools (`list_mcp_resources` / `list_mcp_resource_templates`
  / `read_mcp_resource`, each taking `server`) and publishes server
  `instructions` as system-prompt sections. majo's per-server
  `read_resource` is simpler at majo's scale; realignment is a candidate.
- **`auto` as an approval-policy value** — the reference reserves `auto`
  for a permission *preset* backed by an experimental external review
  layer; majo's session policy `ask|never|auto` is fail-closed and
  documented. Kept.
- **Session format v1** — the reference is at v3 (three-step migration
  chain, worker-isolated verification, optional zstd). majo's v1 + header +
  migration chain mechanism exists precisely to evolve when a real v2 is
  needed; nothing to migrate yet.
- **MCP env-by-name** — majo resolves `${VAR}` names at mount; the
  reference takes explicit values over a scrubbed ambient env. majo now
  also scrubs the ambient env (adopted above), and keeps name-references
  because credentials never appear in profile files.

## Candidates for future cycles (reference has, majo lacks)

- **MCP hardening** ✅ partially adopted 2026-09-18: reconnect policy
  (exponential backoff with attempt budget + stability reset; startup
  failures governed separately by `failOnStartupError`), server-name
  validation (`[A-Za-z0-9_-]{1,32}`). Still candidates: server
  `instructions` as system-prompt sections and shared resource tools.
- **`context` family** ✅ adopted 2026-09-18 (`majo-context`): request-
  context injection plugins — workspace instructions (AGENTS.md /
  CLAUDE.md loading) and time context as durable one-shot `CONTEXT_NOTE`
  events; `@file` mentions and cross-session snapshots remain candidates.
- **`spill` family** ✅ adopted 2026-09-18 (`majo-spill`): oversized tool
  results stored out-of-band behind a locator (`spill_read`), preview +
  retrieval guidance inline; opt-in via `maxInlineBytes` (web profiles:
  16 KiB), fail-open on storage errors, retrieval exempt from re-spilling.
- **`ssh` family**: `fs`/`subprocess`/`sandbox` providers re-pointed at a
  remote host over one OpenSSH connection ("one execution world").
- **`ptc-runtime`**: programmatic tool calling (`run_code` — the model
  writes one program against host-bound tools instead of N calls).
- **`guard` repeat-call reminder** (advisory, tiny).
- **`hooks` compatibility**: running Claude Code / Codex `hooks.json`
  command hooks inside the waterfall surface.
- **`llm-mock-server` fault vocabulary**: stall, wrong content type,
  context overflow, quota, weighted random — richer than majo's fault
  scripts.
- **Larger architecture themes** (each a cycle of its own): `typert` +
  `api` Remote layer (typed Client→Host RPC), `preset` (per-session agent
  composition), `storage`/`workspace`/`webhook`/`feedback`/`identity`,
  `lsp`, `extensions` (agent-modifiable runtime), `bundle` profile
  layering, `experimental/agent-team`.

---

# Round 2 — 2026-09-30: deep diff against dsh 0.2.0-rc.1

The reference moved ~1,961 commits and three releases (0.1.5-rc.3 →
0.1.7-rc.x → 0.2.0-rc.1) since Round 1; packages 58 → 61. A five-domain
parallel audit (core loop / LLM+context / tools / session+workflow / web+runtime)
produced this consolidation. The center of gravity upstream has shifted to
**core hardening** (goal system, cancellation, multimodal LLM vocabulary) and
a **host product layer** (storage/workspace/session-query/hooks/webhook/
preset/desktop) that majo deliberately does not chase at its scale.

## 1. Upstream-new, majo-whitespace

- **Goal family** (largest new theme): event-sourced goal domain
  (`packages/goal/goal/src/index.ts` — phases, CAS revision, maxGoalRounds),
  auto-continue driver (`goal-round-driver`), `get_goal`/`create_goal`/
  `update_goal` tools (`tool-goal`), `/goal` command (`command-goal`).
  Nothing in majo; not on any roadmap yet.
- **Cancellation system**: typed `TurnEndReason` with `aborted(cause)` +
  `CancelOptions.keepInbox` (`packages/core/session/src/types.ts`), synthetic
  `TOOL_ABORTED_BEFORE_DISPATCH` results keeping replay valid
  (`core/agent-loop/src/tool-calls.ts`), archive-admission auto-cancel
  (`core/agent/src/archive-admission.ts`). majo has zero cancel/abort code.
- **Multimodal + metering LLM vocabulary**: content blocks (Text/Reasoning/
  Image/File) + StreamChunk protocol (`packages/llm/llm/src/types.ts`),
  `TokenUsage` with cacheRead/cacheWrite, image token pricing
  (`llm-deepseek/src/image-tokens.ts`), Files API, model discovery, retry
  executor (`packages/llm/llm-retry`). majo `ChatMessage` is String content,
  `ChatResponse` carries no usage, no retry.
- **Host product layer**: `storage` (non-session KV persistence with schema +
  change events, `packages/storage/storage-domain/`), `workspace` (durable
  project registry, archive-not-delete), `session-query` (SQLite FTS5 +
  lineage trace, `packages/session-query/session-query-sqlite/`),
  `hooks` compatibility bridges for Claude Code/Codex hooks.json
  (`packages/hooks/hook-protocol/`), `webhook` (signed GitHub intake),
  `feedback`, `identity` (anonymous UUID), `preset`/`persona` (per-session
  agent composition), desktop (Electron shell).
- **Extended tool families**: browser-use/computer-use (experimental,
  capability-slot registries + MCP providers, unpacked), terminal PTY
  (6 tools, `packages/terminal/tool-terminal/`), lsp (1 tool, 4 read
  operations), document office→pdf service, attachment admission +
  `read_image`.

## 2. Both sides present, upstream deeper (alignment debt)

- **FS write family — the hardest practical gap**: majo's `majo-fs` ships
  only `read_file` + `git_status`; dsh has read/write/edit/glob/grep/
  read_image/str_replace_editor (`packages/fs/tool-fs/`). The majo model can
  write **nothing**.
- **Dual inbox**: persistent splice events + message ids + dedup
  (`core/agent-loop/src/inbox.ts`) vs majo's in-memory string queues
  (`AgentInbox.java`).
- **Turn end reasons**: typed map (error/max-tokens/aborted/forked) vs
  majo's fieldless TURN_END.
- **Approval**: durable policy events + abort-withdraw + late-answer discard
  (`packages/interaction/user-approval/`) vs ask/auto/never pairs, no
  withdraw.
- **Repeat-call reminder**: normalized deep keys, denied calls count, user
  interruption resets the chain (`packages/guard/repeat-tool-reminder/`) vs
  simple counting.
- **Compaction**: region selection with priced tail retention + tool-pairing
  guard + start/end transaction + checkpoints
  (`packages/compaction/compaction-basic/src/region.ts`) vs whole-history
  summary.
- **Instruction context**: user-global + project chain, fs-triggered refresh,
  65,536-byte budget (`packages/context/agent-instructions/`) vs one-shot
  root injection.
- **Skill**: scope chain + rank + bundled + a single `skill` tool with a
  persistent catalog message (`packages/skill/tool-skill/`) vs folder scan +
  two tools.
- **PTC**: programs call host tools via `tools.name(args)` nested dispatch,
  sandbox integration, escalation approval
  (`packages/core/tools/src/ptc.ts`) vs majo's "tools are NOT callable in v1"
  (`PtcService.java`).
- **Jobs**: absolute-offset ring buffer with arbitrary-offset reads +
  archive-admission kill (`packages/jobs/jobs-local/src/ring.ts`) vs tail
  truncation.
- **Schedule**: every/daily/weekly/**cron** (Vixie 5-field + IANA tz) +
  delivery history + host-level persistence + cold wake
  (`packages/schedule/schedule/src/types.ts`) vs after/every/at + daily/
  weekly sugar, no cron.
- **Subagent**: continuable children (send_message/interrupt/list,
  `packages/subagent/tool-subagent-control/`) + experimental agent-team
  (mailbox/task DAG/roster) vs one-shot sync `delegate_task`.
- **Session format**: upstream is at **v4** (v3→v4 landed:
  `packages/session/session-format-v3-to-v4/README.md`) — Round 1's "v3"
  note was already stale; plus checkpoint policy and persistent projection
  caches majo lacks.

## 3. Deliberate divergences (keep)

- **Workflow shape, inverted**: majo = declarative YAML steps + virtual-thread
  parallel groups + **in-process resume**; dsh = model-authored JS on the PTC
  runtime (`agent()/parallel()/pipeline()`), no resume. Both valid; majo
  leads on resume.
- **majo-only strengths**: in-loop credential redaction (`safe()`), the
  `ALLOW_MODEL_TRIGGER_TAG` approval exemption, OpenAPI contract + metrics +
  generated web types (drift-proof), spill fail-open + registered
  `spill_read`, headroom cap at half-budget.
- **Naming**: `read_file`/`run_shell`/`run_command` vs dsh `read`/`bash` —
  kept for session/test stability; documented.
- **Simplifications that hold**: maxSteps=8 hard fail, steer-while-idle →
  followup, non-persistent pending inbox, step-boundary-only abort checks
  (no mid-request cancellation).

## Disposition

Round 2 fed `docs/roadmap-0.6.md`: P1 fs write family, P2 TokenUsage,
P3 minimal cancellation (typed TURN_END + abort), P4 cron schedules;
P5 hooks bridge, P6 skill/PTC deepening, P7 goal-system design study.
The host product layer (storage/workspace/session-query/preset/desktop) is
explicitly out of scope for majo's single-user-local positioning.

---

# Round 3 — 2026-09-30: incremental against dsh 0.2.1-alpha.1

The reference moved ~453 commits (0.2.0-rc.1 → 0.2.0-rc.2 →
0.2.1-alpha.1). The bulk is web/desktop/UI polish and packaging (npm
channels, plugin-manager pages) — out of majo's scope. Three kernel
clusters matter; two are adopted this cycle, one queued.

## Adopted

1. **Goal round withdrawal on cancellation** (`goal-round-driver`
   9a8d21dfe7): after a Stop/cancel, a queued goal round left in the
   inbox would be claimed FIRST by the next human prompt — admitting a
   stale round and parking the human behind it. dsh withdraws the queued
   round at idle. majo adopts the same semantics: an aborted turn's
   pending attempt is withdrawn from the inbox, and admission now also
   stands down when other turn work is queued (the human outranks the
   goal at every point, not just at offer time).
2. **Schedule reminders denied to delegated children** (`schedule` 7-commit
   cluster): reminders created from a delegated child would fire into a
   session the subagent routing owns — permanently overdue retries. dsh
   refuses at the tool layer by delegation depth. majo adopts: child
   turns run with a delegation depth (InteractionContext), and
   `schedule_create`/`schedule_update` refuse depth > 0 (list/delete
   stay allowed — old tasks can still be cleaned up).
3. **Reminder framing as scheduled user messages** (af39300572): due
   reminders are now framed as `[SCHEDULE REMINDER] This is a scheduled
   message from the user` + JSON-encoded metadata (spoof-resistant) in
   the delivered text, instead of trusting the raw prompt.

## Queued (candidate for the next cycle)

- **User-questions timed waits + late replies** (3a316b16b4): an opt-in
  timed ask returns `{pending}` immediately, the turn continues, and the
  user's late answer arrives as a marked user message
  (`answer_to_pending_question`). A real capability upgrade over majo's
  sync-blocking ask (30s → deny) but a bigger interaction-model rework —
  its own cycle.

## Recorded (no action)

- **Runtime invariants removed upstream** (f028f25667): dsh deleted its
  dev-contract invariant plugin, folding the checks into plain tests —
  validating majo's test-only approach (ModelVisibleMeansLoggedTest et
  al.) all along.
- **PTC argument-order guidance in schema descriptions**: minor prompt
  hygiene; majo's `run_code` binds tools dynamically.
- Session-list time slicing, ssh error-name matching, bundle manifest
  hygiene: dsh-scale hosting concerns; nothing to port.
