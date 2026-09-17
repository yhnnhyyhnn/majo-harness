# Roadmap 0.5 (MCP transport + scale)

0.4 shipped durability hardening (session file generations, LLM fault
injection, tool-result pruning) and the stdio MCP client, verified against
the real ecosystem filesystem server. This cycle completes the MCP surface
for remote servers and keeps the scale options visible. Each phase ships
independently; status lives in the CHANGELOG (`## [Unreleased]`).

## Phase 1 — MCP transport + surface completion

- **Streamable HTTP transport**: profile rows gain a `url` + `headers` form
  (header values reference environment variables *by name*, riding
  credentials-by-name) alongside the existing `command` stdio form. Covers
  the tools/list + tools/call lifecycle over Streamable HTTP; auth is
  explicit bearer/custom headers — no OAuth dance (documented limitation).
- **Prompts & resources**: `prompts/list` + `prompts/get` bridge into the
  existing skills/commands seam; `resources/list` + `resources/read` land
  behind a read-only tool (`mcp__<server>__read_resource`). Exact shapes
  are decided at implementation time against real servers.
- Acceptance: an HTTP MCP server mounted from a test (in-process fixture);
  tools, prompts, and resources listed and callable; header env-name
  resolution fails loudly when unset; stdio servers keep working unchanged.

## Phase 2 — Scale options (as needed)

- **SQLite FTS** behind `/api/search` (endpoint unchanged; the per-session
  entry cache stays the default until logs grow).
- **Image offload** (stretch): still deferred unless multimodal usage
  appears.

## Explicitly out of scope for 0.5

Workflow orchestration, agent-team, terminal PTY, ACP/SDK remote surfaces,
desktop app — unchanged from 0.4.
