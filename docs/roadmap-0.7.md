# Roadmap 0.7 (remaining Round-2 alignment debt: compaction region selection, continuable subagents)

Round 2 of the dsh audit is fully dispositioned as of 0.9.0 (P1–P7
shipped). What remains from that audit is the "both sides present,
upstream deeper" tail — alignment debt inside capabilities majo already
has. This cycle takes the highest-value item first; the rest is either
queued or recorded as deliberate divergence. Status lives in the
CHANGELOG (`## [Unreleased]`).

## P1 — compaction region selection ✅

Whole-history summarization was majo's weakest compaction trait — and
goal sessions (0.9.0) now drive many more rounds into it. dsh selects a
compactable region (`packages/compaction/compaction-basic/src/region.ts`):
majo adopts the same shape at its scale —

- **Priced tail retention**: the summary covers only the region up to a
  cut point; a tail priced at `retainTokens` (new config, default
  maxTokens/4) stays verbatim in the derived history. The existing
  `upToSeq` field finally gets its consumer: the deriver folds a
  CONTEXT_COMPACTION by discarding messages contributed at or before
  `upToSeq` and prepending the summary — events after the cut (logged
  before the compaction event) survive.
- **Tool-pairing guard**: the cut never splits an assistant tool round —
  it walks back to before the round's opening ASSISTANT_MESSAGE.
- **Region-scoped summarization**: the summarizer request carries only
  the region's derived messages (cheaper, and the summary cannot
  hallucinate about the tail it never saw).
- Multiple compactions compose (each fold keeps the newer tail).

## P2 — continuable subagents ✅

dsh children continue across turns (`tool-subagent-control`:
send_message/interrupt_agent/list_agents; continuable children seeded
from parent history). majo's `delegate_task` is fire-and-forget. Shape
for majo: child sessions persist; a `session_id` comes back from
delegate; follow-ups ride `loop.followup` into the child session;
list/interrupt round it out.

## Deliberate divergences (recorded, not scheduled)

- **Dual-inbox persistence**: majo's pending inbox is in-memory by
  design (durable enough — everything else rides the log; a crash loses
  only queued notices).
- **Jobs ring buffer**: tail truncation at 8,000 chars is adequate at
  majo's job sizes; arbitrary-offset reads stay out.
- **Instruction-context chain**: single-root injection + two-layer dedupe
  is adequate locally; the project-chain refresh machinery stays out.
- **Approval withdraw**: the sync-blocking ask already fails safe
  (timeout → deny, 30s default); abort-signal withdrawal adds little.
- **Repeat-reminder deepening** (denied calls count, user-interruption
  reset): cheap, taken opportunistically.
- **Multimodal content blocks / mid-request cancellation / retry
  executor**: unchanged from 0.6 — out until a multimodal provider or a
  hard need appears.
