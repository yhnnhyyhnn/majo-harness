# majo-harness v0.7.1 release notes (2026-09-29)

Twelfth tagged release — a maintenance and feature follow-up to v0.7.0.
Everything below keeps `scripts/check.sh` green (no-stdout gate, ESLint,
vitest, full Maven verify across 33 modules). Full item history:
`CHANGELOG.md`.

## Highlights

- **Compaction pressure model** (dsh v0.2.0 alignment): new
  `headroomTokens` config (default 8192) reserves space for the model's
  response — compaction triggers before the context window overflows from
  output tokens alone.
- **`schedule_update` tool**: edit an existing scheduled reminder's prompt
  and/or timing; re-arms with the new parameters.
- **Schedule daily/weekly recurring**: `schedule_create` gains
  `daily: "HH:mm"` and `weekly: {"day":"MONDAY","time":"HH:mm"}` sugar.
- **`git_status` tool**: the model can inspect working-tree changes after
  writing files (read-only, no approval required).
- **System-prompt sections** (`AgentLoopService.registerSystemSection`):
  plugins contribute lazily-evaluated sections assembled in id order;
  REQUEST_HEADER records the assembled prompt verbatim.
- **MCP shared resource tools** (dsh `mcp-resources` shape): shared
  `list_mcp_resources` / `list_mcp_resource_templates` /
  `read_mcp_resource` with a `server` argument.
- **MCP reconnect & startup policy**: lazy reconnect with exponential
  backoff + attempt budget + stability reset; `failOnStartupError`;
  server-name validation.
- **SSH remote-execution family**: `majo-ssh` connection layer +
  `SshFsProvider` / `SshSubprocessProvider` for pointing fs and subprocess
  at a remote host.
- **skill-files**: gracefully skips missing directories.
- **`web-ui/.npmrc`**: points npm cache to a clean location, fixing
  Maven-spawned npm cache corruption.
- **Usage guide** (`docs/usage.md` + zh-CN): quick start, model
  connection, 20-tool walkthrough, key features, safety model.
- **web-parity bilingual** updated for v0.5–v0.7 additions (12 rows).

## Upgrading from v0.7.0

- No breaking changes. New default profile rows: `workflow`, `spill`,
  `ptc`, `context`. Tool count: 22 (was 20 in v0.7.0; new: `git_status`,
  `schedule_update`).
