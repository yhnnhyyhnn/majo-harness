# majo-harness v0.5.1 release notes (2026-09-18)

Seventh tagged release — a fast follow-up to v0.5.0 covering three user-facing
additions (spill, `@file` mentions, MCP reconnect) plus loop-hygiene polish.
Everything below keeps `scripts/check.sh` green (no-stdout gate, ESLint,
vitest, full Maven verify across 29 modules). Full item history:
`CHANGELOG.md`.

## Highlights

- **Spill family (`majo-spill`, the dsh `spill` analog)**: tool results
  whose content exceeds `maxInlineBytes` (opt-in; web profiles: 16 KiB)
  store the full text out-of-band and return a preview + locator — the
  model retrieves the whole output with the `spill_read` tool. Storage
  failure fails open; retrieval is exempt from re-spilling. Complements
  compaction pruning (spill handles the current oversized result, pruning
  handles old ones).
- **`@file` mentions (dsh `file-reference` analog)**: the composer's `@`
  completion lists workspace files (`GET /api/mentions?q=`, VCS/build noise
  skipped); selecting one injects the file as a durable `CONTEXT_NOTE` —
  the model reads referenced files without a tool round-trip. Text-only
  (binary rejected), 64k-char truncation.
- **MCP reconnect & startup policy (dsh analogs)**: dead server connections
  lazily reopen with exponential backoff and an attempt budget (60s
  stability reset); startup failures are governed separately by
  `failOnStartupError` (default false) and never burn the budget; server
  names validate against `[A-Za-z0-9_-]{1,32}`.
- **Repeat-call advisory** (dsh guard repeat-tool-reminder analog): the
  `tools` plugin gains `repeatReminder` — consecutive identical calls get
  an advisory line nudging the model out of loops.
- **Stall fault** (dsh llm-mock-server `stall` analog): `FaultLlmServer`
  can accept a request and stay silent, pinning the client-timeout path.

## Upgrading from v0.5.0

- No API-contract changes; `openapi.json` gained `/api/mentions` and
  `POST /api/sessions/{id}/mentions` (additive).
- New default profile row: `spill` (`maxInlineBytes: 16384`). Remove the
  row (or the key) to keep every tool result fully inline.
- Optional `tools` knobs: `toolTimeoutSeconds` (already 600 in the shipped
  profiles) and `repeatReminder: true` to enable the loop advisory.
