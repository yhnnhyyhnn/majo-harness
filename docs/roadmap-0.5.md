# Roadmap 0.5 (MCP transport + scale) — Phase 1 SHIPPED

0.4 shipped durability hardening (session file generations, LLM fault
injection, tool-result pruning) and the stdio MCP client, verified against
the real ecosystem filesystem server. This cycle completes the MCP surface
for remote servers and keeps the scale options visible. Each phase ships
independently; status lives in the CHANGELOG (`## [Unreleased]`).

## Phase 1 — MCP transport + surface completion

- **Streamable HTTP transport** ✅: profile rows gain a `url` + `headers`
  form (header values reference environment variables *by name*, riding
  credentials-by-name) alongside the existing `command` stdio form. Covers
  the tools/list + tools/call lifecycle over Streamable HTTP (JSON and SSE
  responses, session-id echo, DELETE on unmount); auth is explicit
  bearer/custom headers — no OAuth dance (documented limitation).
- **Prompts & resources** ✅: one namespaced read-only tool per capability —
  `mcp__<server>__read_resource` (resources/read behind it) and
  `mcp__<server>__get_prompt` (prompts/get, rendered `role: text` lines),
  both enumerating the server's offering in the tool description. Prompts
  land as tools rather than the commands seam because the command registry
  lives in `majo-boot`, which already depends on `majo-mcp` — the reverse
  edge would be circular.
- Acceptance ✅: an HTTP MCP server mounted from an in-process fixture;
  tools, prompts, and resources listed and callable over both JSON and SSE
  responses; header env-name resolution fails loudly when unset; stdio
  servers keep working unchanged (suite green).

## Phase 2 — Scale options (as needed)

- **SQLite FTS** behind `/api/search` (endpoint unchanged; the per-session
  entry cache stays the default until logs grow).
- **Image offload** (stretch): still deferred unless multimodal usage
  appears.

## Explicitly out of scope for 0.5

Workflow orchestration, agent-team, terminal PTY, ACP/SDK remote surfaces,
desktop app — unchanged from 0.4.
