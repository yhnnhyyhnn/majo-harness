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

- **MCP hardening**: reconnect policy with attempt budget, server
  `instructions` as system-prompt sections, shared resource tools
  (above), server-name validation.
- **`context` family**: request-context injection plugins —
  `agent-instructions` (AGENTS.md/CLAUDE.md loading), `@file` mentions,
  cross-session read-only snapshots, time context. Durable because logged
  as user-role messages.
- **`spill` family**: oversized tool text stored out-of-band with a
  locator, replacing the inline copy (complements pruning).
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
