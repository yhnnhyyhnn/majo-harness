# Goal system — design proposal (roadmap-0.6 P7)

dsh 0.2.0 introduced the goal family (event-sourced goal domain +
same-session auto-continue driver + tools + `/goal` command). This document
proposes a majo port at majo scale, following the workflow-v1 pattern:
mechanism-faithful where it matters, simplified where majo's single-process
loop allows it. Open scope questions are listed at the end for decision
before implementation.

## Motivation & non-goals

A goal is a durable objective that keeps a session working across turns:
create it once and the session drives toward it round after round — while
remaining interruptible by the human at every boundary (pause/clear wins,
the model cannot lock the harness in). This is the missing piece between
one-shot turns and declarative workflows: workflows script the steps; a
goal states the destination and lets the model find the steps.

Non-goals: cross-session goals, goal DAGs / dependencies, a separate goal
store (goals live in the session log like everything else), UI beyond an
optional read-only panel.

## Domain model (dsh `packages/goal/goal`, majo-shaped)

One goal per session, event-sourced in the log:

- `Goal` record: `goalId` (`goal-<n>`), `revision` (starts 1, +1 per
  durable change), `objective` (text), `phase` (`active|paused|blocked|
  complete`), `maxRounds` (default 256, create-time override),
  `roundsStarted` (admitted goal rounds only), `blockedReason`
  (`{code, message}` when blocked), `createdAt`/`updatedAt`.
- `clear` writes a tombstone (goal nulled, `seenGoalIds` keeps the id so a
  cleared id is never reused); create is allowed again afterwards.
- Persistence: one new durable event **`GOAL_CHANGE`** carrying the full
  snapshot (`operation`, goal fields, rounds) or the clear tombstone —
  the TodoState/PlanState projection pattern (`majo-session` already has
  the fold machinery). The projection validates the fold: strict
  revision+1, legal phase transitions (`pause`: active→paused; `resume`:
  paused/blocked→active; `complete`: any live→complete; `block`:
  active→blocked only), create only when no live goal exists, no id reuse.
  A first invalid event poisons the projection with a loud failure, host
  access throws.
- **CAS stale-write protection**: every mutating call carries
  `(goalId, revision)`; mismatch throws `GOAL_STALE_REVISION` (dsh parity).

## Round source plumbing (the key majo seam)

dsh tags messages with a `source` (`user`/`goal`/…); absent source on
`followup()` means human. majo adopts the same vocabulary:

- `AgentInbox` turn entries become `(text, source)` pairs;
  `AgentLoopService.followup(sessionId, text)` keeps meaning
  `source=user` (existing callers — jobs/schedule — unchanged), and a new
  `followup(sessionId, text, Map source)` carries explicit sources.
- `USER_MESSAGE` events gain a `source` field (`user` default; `goal`
  rounds carry `goalId`+`revision`+`round`). Legacy logs (no field) read
  as `user`.
- `agent/user-submit` moves **before** the `TURN_START` append (nothing
  else logs in between): a listener rejection then leaves no open turn —
  the driver needs exactly this for clean round rejection, and hooks
  keep working (they already must be idempotent about the log).

This plumbing is reusable beyond goals (a future `dsh`-style initiator
discipline for jobs/schedule rides the same field).

## Round driver (dsh `goal-round-driver`, majo fences)

Armed/disarmed activation is process-local volatile state (fresh process =
disarmed — restart parity with dsh; `resume` re-arms). A `GoalRoundDriver`
(listening to goal changes + the loop's `agent/turn-closed`) drives:

- **Admission model**: when idle-adjacent + armed + active +
  `rounds < maxRounds`, render the round prompt (`<goal_round>` block:
  objective JSON + `Round: n/max`) and `followup(..., source=goal)`.
- **Fences, majo scale** (dsh has five; we keep the load-bearing three):
  1. *Reservation check at submit*: the driver registers a pending
     attempt; on `agent/user-submit` with the goal source it validates
     current goal id+revision, phase active, armed,
     `round == roundsStarted+1` — mismatch rejects (no round consumed).
  2. *Competing work*: if the inbox holds any non-goal turn entry when
     the attempt is validated, the driver stands down (attempt dropped,
     disarm pending re-evaluation) — any human prompt outranks the goal
     until the session is idle again.
  3. *maxRounds*: on admission with `rounds >= maxRounds`, the driver
     blocks the goal (`round-limit`) instead of queuing.
  Documented simplifications: no per-message-id dedup (majo's inbox is
  in-memory), no flush checkpoint (single process, log append is already
  durable-per-event).
- **Host-pause vs model-pause**: `GoalService.pause(byHost=true)` (from
  `/goal pause` or the API) additionally calls `loop.abort(sessionId)` —
  the running turn closes `aborted` and the goal is paused. A
  model-initiated pause (via the tool inside its own turn) converges
  normally. dsh parity via majo's new minimal cancellation.
- Turn ends `aborted` while a goal attempt is in flight → attempt dropped,
  goal stays armed (a fresh round may start); `max-tokens`-style failure
  stays impossible at majo's maxSteps (loud fail leaves the goal armed,
  driver re-evaluates on next idle).

## Tools (dsh `tool-goal` + authority)

Three tools + one static system section (`goal-tools`: the rules below,
with the N threshold interpolated):

- `get_goal` — snapshot or "no active goal".
- `create_goal` — **requireDirectHuman**: only valid when the open turn
  contains a real user message (`source=user`). The model cannot
  self-start goals.
- `update_goal` with actions:
  - `edit` (objective/maxRounds) — requireDirectHuman; phase immutable.
  - `pause` / `resume` — requireDirectHuman; the model resuming a paused
    goal is rejected outright (`GOAL_TOOL_RESUME_PAUSED`).
  - `complete` — direct human **or** inside a current goal round
    (authority = the open turn matches this goal's id/revision/round).
  - `blocked {code, message}` — like complete, plus the hard floor:
    `roundsStarted >= blockedAfterConsecutiveRounds` (default 3; human
    requests bypass the floor). majo keeps dsh's honest semantics: the
    runtime counts rounds, the model owns the "same condition persists"
    judgment.
- All mutations take `(goal_id, revision)` (CAS) and fail loudly when
  called outside a session-bound turn.
- **Wrapup**: after an autonomous (goal-round authority) complete/blocked,
  the tool injects a `<goal_complete>`/`<goal_blocked>` CONTEXT_NOTE
  (objective/reason + closing-message instructions) — majo's
  `loop.inject` lands it at the next step boundary of the same turn.
  Human-initiated changes inject nothing (dsh parity).

`requireDirectHuman` reads the open turn's `USER_MESSAGE` sources — the
same round-source plumbing the driver uses; no extra initiator machinery.

## `/goal` command (dsh `command-goal`, via `majo-boot` CommandRegistry)

`/goal` (show), `/goal <objective>` (create), `/goal edit <objective>`,
`/goal pause`, `/goal resume`, `/goal clear`. Human commands are the
direct-human authority by construction (they run from the UI/console, and
the command records its change with `byHost=true`). Composer-attachment
pre-commit stays out of scope (majo's composer has no goal attachment
flow).

## Storage & API surface

- Events: `GOAL_CHANGE` (+ `USER_MESSAGE.source` field) — both added to
  the typed projection registry; the fold never feeds the model history
  (the round text itself is the user message).
- Optional Phase C: `GET /api/sessions/{id}/goal` (snapshot for the UI)
  and a read-only goal chip; not required for the mechanism.

## Phases

- **Phase A** (core): round-source plumbing (inbox/source field/
  user-submit reorder) + `GoalService` + `GOAL_CHANGE` projection +
  `get/create/update_goal` tools + `goal-tools` system section.
- **Phase B** (autonomy): round driver with the three fences + wrapup
  injection + host-pause abort + `/goal` command.
- **Phase C** (optional): goal snapshot API + UI chip.

## Acceptance

- CAS: stale-revision updates fail loudly; fold rejects illegal
  transitions; clear tombstone forbids id reuse.
- Authority: create/edit/pause/resume without a human turn fail;
  complete/blocked inside a goal round succeed; blocked below N rounds
  fails; resume of a paused goal by the model fails.
- Driver: an armed active goal produces round prompts of the exact
  rendered form; a competing human followup stands the driver down; round
  limit blocks with `round-limit`; host pause aborts the running turn;
  restart leaves the goal disarmed.
- All of it rides the log: no goal state outside session events +
  process-local activation.

## Open questions (for decision before implementation)

1. **Scope**: full port (Phase A+B, `/goal` included — recommended) vs
   Phase A only (state board, no auto-continue) vs A+B without `/goal`.
2. **Authority strictness**: keep requireDirectHuman exactly as dsh
   (recommended — the model cannot self-start/pause/resume goals) vs
   relax it (model may create goals autonomously; smaller, but removes
   the human-in-the-loop guarantee that makes goals safe).
3. **blocked floor**: keep 3 (recommended) vs make it config-only with a
   different default.
