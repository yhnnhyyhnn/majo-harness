# majo-harness v0.7.0 release notes (2026-09-19)

Eleventh tagged release — introduces **PTC (programmatic tool calling)**,
letting the model write a single JavaScript program instead of making N
discrete tool calls. Everything below keeps `scripts/check.sh` green
(no-stdout gate, ESLint, vitest, full Maven verify across 32 modules).
Full item history: `CHANGELOG.md`.

## Highlights

- **PTC runtime (`majo-ptc`, dsh `ptc-runtime` analog)**: a new
  `run_code` tool executes model-written JavaScript in a fresh Node.js
  process and returns the captured stdout. Use it for multi-step
  computation, JSON transformation, math, string processing — any logic
  that would otherwise require multiple tool calls. Config:
  `{nodePath: "node", timeoutSeconds: 30}`. Syntax errors and timeouts
  surface as ordinary tool errors (model-visible); blank code fails
  loudly. The shipped profiles mount `ptc` with a 30-second timeout.
- **SSH remote-execution family** (from v0.6.2, included here for
  completeness): the `fs` and `subprocess` plugins gain an optional
  `ssh: {host, user, port?, identityFile?}` config for pointing file and
  command execution at a remote host via the OpenSSH CLI.
- **Workflow Phase B** (from v0.6.1): `parallel: true` step groups,
  `/workflow status`, and in-process `/workflow resume`.

## Upgrading from v0.6.2

- No breaking changes. The `ptc` profile row is optional; remove it to
  skip the `run_code` tool. Requires Node.js on PATH (already needed for
  the web UI build).
- Module count: 32 (new: `majo-ptc`); tool count: 20 (new: `run_code`).
