# Workflow v1 — design + Phase A SHIPPED (2026-09-18)

dsh's workflow package (`packages/workflow`) is the largest deferred
feature. This document proposed a pragmatic v1; the three open questions
were reviewed and decided (YAML; child-session execution; `workflow_run`
gated by default with a definition-level `allowModelTrigger` opt-out), and
**Phase A is implemented** (`majo-workflow`: parser + runner + `/workflow`
command + `workflow_run` tool + trajectory labels; sample
`workflows/review-doc.yml`). Phase B remains future work.

## Motivation & non-goals

Real tasks are multi-step: research → draft → review, or fan-out a task to
several children and merge. The building blocks already exist (delegations
with scoped models/tools, jobs, the inbox); what's missing is **reusable,
named orchestration**.

Non-goals for v1: a general-purpose programming language (no loops/branches
beyond `onFailure`), a visual editor, cross-session coordination, versioned
workflow registries.

## Core model

A workflow is a named list of steps, declared in YAML under `workflows/`
(repo-relative, like `skills/`):

```yaml
name: review-doc
description: Draft a review of a document with two independent reviewers
steps:
  - id: fetch
    kind: turn
    prompt: "Read {{args.path}} and extract the document's key claims."
  - id: review
    kind: delegate
    prompt: "Review these claims for factual errors:\n{{steps.fetch.output}}"
    model: kilo-free          # optional per-step override
    allowedTools: [read_file]  # optional per-step whitelist
  - id: merge
    kind: turn
    prompt: "Merge the reviews into a final verdict:\n{{steps.review.output}}"
onFailure: abort            # or: continue
```

- **`turn` steps** run as a model turn inside the run's session.
- **`delegate` steps** fan out via the existing `delegateSpec` seam (scoped
  model / maxSteps / allowedTools / islands), parallel when a step declares
  `parallel: true` across multiple `delegate` siblings.
- **Templates**: `{{args.*}}` and `{{steps.<id>.output}}` string
  interpolation, resolved before each step. No expressions, no conditions —
  a missing key fails the step loudly.
- **Runs execute in a dedicated child session** (scoped via the existing
  delegation seam), so the user's conversation log stays clean; the run
  posts one summary message back to the requesting session at the end.

## State & observability

- New durable session events `WORKFLOW_START` / `WORKFLOW_STEP` /
  `WORKFLOW_END` in the requesting session (run id, step id, status,
  duration) — the Trajectory view renders them for free, and the
  "model-visible means logged" invariant holds because workflow events are
  bookkeeping (skipped by derivation, like TODO_SET).
- Crash semantics: a run is not resumable in v1; a crashed run simply ends
  with `WORKFLOW_END(status=failed)`. Resume is a v2 candidate.

## Triggers

1. `/workflow <name> [json-args]` — a host command (registered when the
   module mounts), with the command registry supplying discoverability.
2. A `workflow_run` tool (name/description/JSON-schema generated from the
   `workflows/` directory at mount) so the *model* can start workflows too.

## Failure & approvals

- Default `onFailure: abort`; already-completed steps are kept in the
  events.
- Steps that need gated tools ride the ordinary approval seam inside their
  session — no special workflow approval path in v1.

## Phasing

- **Phase A (this proposal's implementation scope)**: definition parsing +
  validation, the runner (turn/delegate steps, templates, events),
  `/workflow` command, `workflow_run` tool, trajectory rendering.
- **Phase B**: parallel step groups, run listing/`/workflow status`,
  resume.

## Open questions (for review)

1. YAML vs JSON for definitions (proposal: YAML, matching `skills/`).
2. Child-session execution (proposal: yes, always) vs in-session steps.
3. Should `workflow_run` be approval-gated by default? (proposal: yes —
   it can fan out costly work.)
