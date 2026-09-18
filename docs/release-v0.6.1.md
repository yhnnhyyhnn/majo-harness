# majo-harness v0.6.1 release notes (2026-09-18)

Ninth tagged release — a fast follow-up completing the dsh-shape MCP
alignment and Workflow Phase B. Everything below keeps `scripts/check.sh`
green (no-stdout gate, ESLint, vitest, full Maven verify across 32
modules). Full item history: `CHANGELOG.md`.

## Highlights

- **Workflow Phase B**: consecutive steps marked `parallel: true` fan out
  concurrently (virtual threads, barrier-tested); `/workflow status` lists
  recent runs (bounded in-memory records with per-step statuses);
  `/workflow resume <runId>` restarts a failed run in-process from its
  first non-ok step, replaying recorded outputs and arguments.
- **MCP shared resource tools** (dsh `mcp-resources` shape):
  `list_mcp_resources`, `list_mcp_resource_templates`, and
  `read_mcp_resource` replace the per-server read-only tool — one call
  aggregates across servers, with an optional `server` filter.
- **MCP server instructions as system-prompt sections** (dsh
  `server-context` shape): each server's initialize-time instructions plus
  the usable-server list contribute a lazily-evaluated `mcp:` section to
  the system prompt. `AgentLoopService.registerSystemSection(id, supplier)`
  is the new generic seam — sections assemble in id order after the
  configured prompt, and `REQUEST_HEADER` records the assembled prompt
  verbatim (the invariant is unaffected).

## Upgrading from v0.6.0

- No breaking changes. The per-server `mcp__<server>__read_resource` tool
  is replaced by the shared `read_mcp_resource` (`server` + `uri`
  required); prompts stay as `mcp__<server>__get_prompt`.
- Profiles mounting `mcp` must also mount `agent-loop` (the section
  contribution declares it as an injection) — the shipped profiles already
  do.
