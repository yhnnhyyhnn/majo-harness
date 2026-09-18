# majo-harness v0.6.0 release notes (2026-09-18)

Eighth tagged release — headlined by **Workflow v1**, the harness's first
reusable orchestration surface, plus three resilience/UX additions.
Everything below keeps `scripts/check.sh` green (no-stdout gate, ESLint,
vitest, full Maven verify across 31 modules). Full item history:
`CHANGELOG.md`.

## Highlights

- **Workflow v1 (`majo-workflow`)** — designed in
  `docs/workflow-design.md` and reviewed before implementation:
  - workflows are named YAML step lists under `workflows/`
    (sample `review-doc.yml` ships);
  - steps run **in order in scoped child sessions** via the delegation
    seam — `turn` steps inherit harness defaults, `delegate` steps honor
    per-step `model` / `systemPrompt` / `maxSteps` / `autoApprove` /
    `allowedTools`;
  - data flows only through explicit `{{args.*}}` /
    `{{steps.<id>.output}}` templates — no expression language, missing
    keys fail the step loudly;
  - `WORKFLOW_START` / `WORKFLOW_STEP` / `WORKFLOW_END` durable events
    land in the requesting session (rendered by the Trajectory view,
    skipped by derivation — invariants intact);
  - triggers: `/workflow [name [json-args]]` command and the
    `workflow_run` tool; the tool is **approval-gated by default** in the
    shipped profiles, and a definition's `allowModelTrigger: true` opts
    out via a spec-description allow-tag honored by the approval gate;
  - `onFailure: abort | continue`.
- **Spill family (`majo-spill`)**: tool results over `maxInlineBytes`
  (opt-in; shipped profiles: 16 KiB) store the full text out-of-band and
  return a preview + locator — `spill_read` retrieves the whole output.
  Fail-open on storage errors; retrieval never re-spills.
- **`@file` mentions (dsh `file-reference` analog)**: composer `@`
  completion over the workspace (`GET /api/mentions`); selecting a file
  injects it as a durable `CONTEXT_NOTE` (`POST
  /api/sessions/{id}/mentions`) — text-only, 64k truncation, binary
  rejected.
- **MCP reconnect & startup policy**: dead server connections lazily
  reopen with exponential backoff and an attempt budget (60s stability
  reset); startup failures are governed separately by
  `failOnStartupError` (default false); server names validate against
  `[A-Za-z0-9_-]{1,32}`.
- **Loop hygiene**: `tools` gains `toolTimeoutSeconds` and
  `repeatReminder` (advisory line on consecutive identical calls); the
  LLM fault server gained a `stall` script pinning client-timeout paths.

## Upgrading from v0.5.1

- No breaking API changes; `openapi.json` gained the mentions endpoints
  (additive).
- New default profile rows: `workflow` (`dir: workflows`) and `spill`
  (`maxInlineBytes: 16384`); `tool-approval` now also gates
  `workflow_run` — remove the name from the row to let the model run
  workflows freely (the per-definition `allowModelTrigger` tag still
  applies).
- Write your own workflows into `workflows/*.yml` next to the repo's
  sample; `/workflow` lists everything found.
