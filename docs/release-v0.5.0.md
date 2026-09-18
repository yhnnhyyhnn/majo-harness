# majo-harness v0.5.0 release notes (2026-09-18)

Sixth tagged release: the harness learns **where it is** (workspace
instructions and time injected into every session), goes **remote** with
hardened MCP, and tightens its loops (tool timeouts, cheaper hot paths).
Everything below keeps `scripts/check.sh` green (no-stdout gate, ESLint,
vitest, full Maven verify across 28 modules). Full item history:
`CHANGELOG.md`.

## Highlights

- **Request-context injection (`majo-context`, the dsh `context` family)**:
  once per session, at the first turn start, workspace instructions
  (`AGENTS.md` / `CLAUDE.md`, read at mount) and the current time land as a
  durable `CONTEXT_NOTE` — model-visible user-role content that replays,
  survives resume, and compacts like any other context. Two-layer dedupe
  (in-process set + log-marker scan) keeps restarted hosts from
  re-injecting. Mounted by default in the web profiles; config
  `{dir, files, timeContext, maxChars}`.
- **MCP Streamable HTTP transport**: server rows accept `url` + `headers`
  (env names by reference) alongside `command` stdio; JSON and SSE response
  shapes, `Mcp-Session-Id` echo, `DELETE` teardown. **Prompts & resources**
  land as namespaced read-only tools (`mcp__<server>__get_prompt`,
  `mcp__<server>__read_resource`). **Verified against the real ecosystem**:
  the official filesystem server over stdio (npx, 14 tools) and the hosted
  `mcp.deepwiki.com/mcp` over HTTP — both probes ship in-repo
  (`MAJO_MCP_PROBE=1`) and run best-effort in CI.
- **MCP stdio env scrubbing** (dsh `scrubbedParentEnv` analog): spawned
  servers see only an allowlisted subset of the ambient environment plus
  the row's explicit `env` — ambient credentials cannot leak.
- **Tool-call timeout** (dsh guard timeout-policy analog): `tools` plugin
  gains `toolTimeoutSeconds` (web profiles: 600s) — a hung call returns a
  clear timed-out error instead of stalling the turn forever.
- **Hot-path hardening**: append sequence numbers memoize per session and
  derived titles memoize once — sidebar polls no longer parse any session
  log.
- **Tool-result pruning keeps head+tail** (dsh
  compaction-tool-result-pruner shape): pruned older results retain 4096/
  1024 chars behind a marker line; threshold aligned to 8192.
- **LLM-backed session titles** (`session-title-llm` row, mount instead of
  the heuristic row): memoized per session with per-prompt failure backoff.
- **Composer font-size control**; **Workflow v1 design proposal**
  (`docs/workflow-design.md`, awaiting review).

## Upgrading from v0.4.0

- No API-contract changes; `openapi.json` is untouched.
- New default profile rows: `context` (workspace instructions + time), and
  `tools` now carries `toolTimeoutSeconds: 600`. To disable either, remove
  the config key / the row.
- A session-level title style change (optional): mount
  `session-title-llm` instead of `session-title-heuristic` to have the
  model propose titles (sole-provider semantics — never both).
- MCP servers can now also be declared remote:
  `{url: https://…, headers: {Authorization: Bearer ${ENV_VAR}}}`.
