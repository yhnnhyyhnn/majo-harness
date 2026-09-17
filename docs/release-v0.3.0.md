# majo-harness v0.3.0 release notes (2026-09-18)

Fourth tagged release: the roadmap-0.4 cycle — **durability & test
hardening (Phase 1)** plus the **MCP client (Phase 2)**, the harness's
first standard-protocol extension surface. Everything below keeps
`scripts/check.sh` green (no-stdout gate, ESLint, vitest, full Maven verify
across 27 modules). Full item history: `CHANGELOG.md`.

## Highlights

- **MCP client (`majo-mcp`, Phase 2)**: profile rows declare stdio MCP
  servers (`command` / `args` / `env` — env entries reference environment
  variables *by name*, credentials never live in the profile). The plugin
  spawns each server, runs the JSON-RPC 2.0 `initialize` handshake, and
  bridges `tools/list` into the `ToolRegistry` as namespaced
  `mcp__<server>__<tool>` tools with verbatim JSON schemas — they appear in
  `/api/tools` and the generated tool catalog for free, and every call
  rides the ordinary approval seam (`ask` / `never` / `auto`, fail-closed).
  Calls are bounded by `requestTimeoutSeconds` (default 10); MCP errors
  surface as ordinary tool errors; a failed mount is logged loudly but
  never breaks the boot or the other servers; unmount closes the server
  processes. Verified end-to-end against an in-repo echo MCP server spawned
  as a real subprocess.
- **Session file generations (Phase 1)**: durable session logs are now
  named `<id>.v1.jsonl` and start with a one-line format header
  (`SessionFileFormat`). Header-only `stat`/`listStats` mean directory
  listings never parse event bodies; the sessions listing endpoint stopped
  full-parsing every log per poll (`eventCount` seam). Legacy unversioned
  files migrate in one step on first touch, newer generations fail loudly
  (downgrade protection), and a crash's partial trailing line is repaired
  on read — a complete corrupt line still fails loudly.
- **LLM fault injection (Phase 1)**: `FaultLlmServer` — a raw-socket
  scripted HTTP server controlling the exact wire bytes, including lying
  about `Content-Length` for real mid-body disconnects — plus
  `FaultChatModel`, the seam-level wrapper that throws on the n-th call or
  kills a stream after n deltas. Wire tests pin the provider contract:
  disconnects, 429/5xx, malformed SSE chunks, in-stream error objects, and
  non-JSON bodies all fail loudly as `ModelException`, never as a silently
  truncated "success", with no hidden adapter retry. Loop tests pin the
  turn contract: a model failure fails the turn loudly, the session log
  keeps only the open turn (no half-committed assistant round), and a
  failed driver turn cannot wedge the inbox queue.
- **Tool-result pruning (Phase 1)**: in derived history, tool results older
  than the final assistant round collapse to
  `[pruned tool result: N chars]` placeholders once they exceed the
  configurable threshold (`pruneChars`, default 4000); the newest round
  stays intact and the durable log is untouched — pruning is a
  deterministic function of the log, so the request == system +
  prune(derive(log)) invariant holds at ask time.
- **Release pipeline**: `scripts/release.sh` now also cuts from a tree
  sitting at the previous release (no dev `-SNAPSHOT` open) — this release
  is the first to exercise that path.

## Upgrading from v0.2.0

- No API-contract changes; `openapi.json` is untouched.
- Existing session logs migrate transparently: the first touch of each
  session renames `<id>.jsonl` to `<id>.v1.jsonl` and prepends the format
  header. No action needed; keep old files writable.
- The `mcp` profile row is optional and empty by default. To attach a
  server:

  ```yaml
  - id: mcp
    name: mcp
    config:
      servers:
        filesystem:
          command: npx
          args: ["-y", "@modelcontextprotocol/server-filesystem", "/some/dir"]
  ```

  Its tools then show up as `mcp__filesystem__<tool>` everywhere tools do.
