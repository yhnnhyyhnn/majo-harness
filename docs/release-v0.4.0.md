# majo-harness v0.4.0 release notes (2026-09-18)

Fifth tagged release: MCP goes **remote** and the harness gets a live-probe
habit. Everything below keeps `scripts/check.sh` green (no-stdout gate,
ESLint, vitest, full Maven verify across 27 modules). Full item history:
`CHANGELOG.md`.

## Highlights

- **MCP Streamable HTTP transport**: server profile rows accept a `url` +
  `headers` form alongside the existing `command` stdio form — remote MCP
  servers work without a local process. The client speaks Streamable HTTP
  (JSON and SSE response shapes), echoes the `Mcp-Session-Id` from
  initialize on later calls, and `DELETE`s the session on unmount. Header
  values reference environment variables *by name* (bearer/custom headers
  only — OAuth is a documented limitation). A row must carry exactly one of
  `command`/`url`; violating rows fail loudly without breaking boot.
- **MCP prompts & resources**: servers declaring the capability get one
  namespaced read-only tool each — `mcp__<server>__read_resource` and
  `mcp__<server>__get_prompt` — with the server's offering enumerated in the
  tool description at mount time.
- **Verified against the real ecosystem, both transports**: the stdio
  client round-trips `@modelcontextprotocol/server-filesystem` via npx
  (handshake, 14 tools, read_file), and the HTTP client round-trips the
  hosted `mcp.deepwiki.com/mcp` server (handshake, tool list,
  `read_wiki_contents` on real content). Both probes ship in the repo,
  gated by `MAJO_MCP_PROBE=1`, and run best-effort in CI.
- **Hot-path hardening**: session-append sequence numbers are memoized per
  session (one log parse per session per process; `remove` forgets the
  memo, imports extend it), and derived session titles memoize once — the
  sidebar poll no longer re-parses any session log per cycle.
- **Composer font-size control** (dsh ui-theme stretch): a header toggle
  cycles small/medium/large, persisted in localStorage.

## Upgrading from v0.3.0

- No API-contract changes; `openapi.json` is untouched.
- To attach a remote MCP server:

  ```yaml
  - id: mcp
    name: mcp
    config:
      servers:
        deepwiki:
          url: https://mcp.deepwiki.com/mcp
        private:
          url: https://mcp.internal.example/mcp
          headers:
            Authorization: Bearer ${MY_TOKEN_ENV_VAR}
  ```
