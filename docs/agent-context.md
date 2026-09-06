# Per-agent context tree (milestone design)

Status: **design** — the seed pieces already shipped are per-turn model /
system-prompt overrides and parallel `delegate_task` execution
(`parallelDelegates`). This doc designs the next milestone: giving each child
agent an isolated, disposable **agent scope** built on jcordis context trees.

## Background / where we are

- The harness runs one root `Context` (`Context.create()`) per process; every
  profile row mounts one plugin instance on that single context.
- A turn belongs to a **session** (durable event log). `AgentLoopService`
  drives a session: model completion + tool rounds + approval/ask seats.
- Subagents today: `delegate_task` → fresh child **session** on the *same*
  context, same loop service, same model registry/tools/settings. Isolation
  is limited to: fresh history (new session), optional per-agent
  `model`/`systemPrompt` (5-arg `runTurn`), and opt-in parallel execution of
  the delegate calls in a tool round.
- Web serves turns serially per instance (one `turnLock`), which is fine for
  a single-user local server but caps concurrent agent work.

## Goal (this milestone)

A child delegation runs as its own **agent scope**:

1. scoped configuration — model, system prompt, max steps, allowed tools,
   sandbox/allowlist profile, approvals policy;
2. scoped service views — the child sees *its* LLM facade and tool view, not
   the global mutable singletons;
3. lifecycle — scope boots once per delegation, rolls back (Disposable) when
   the delegation ends, including its registrations and any pending
   interactions;
4. concurrency — many scopes run on virtual threads; durable events still
   land in the shared session store so transcripts, projections, titles and
   the web UI stay coherent.

Non-goals: per-agent *isolated disk/file state* and per-agent *plugin jar
islands* in this milestone; a child does not mount arbitrary new profile rows.

## Foundation: jcordis context trees

`io.jcordis.core.context.Context` already provides the needed primitives:

- `extend()` / `extend(Map meta)` — child context inheriting the parent's
  isolation and interception;
- `intercept(name, config)` — child context that overrides the config a
  service of `name` will be constructed with;
- `isolate(name[, key])` — fresh (or shared) service-key per child;
- `child(Fiber)` — a context bound to a specific fiber;
- plugins mount per context (`ctx.plugin(plugin, config)`) with dependency
  epochs, and `fiber().disposeAsync()` tears a context's contributions down.

This means we do **not** need a new kernel: an agent scope is a small
`Context.extend()` subtree that (re)mounts a scoped loop service (and later a
scoped tools view) with intercepted config, then is disposed at delegation
end.

## Proposed shape

```
AgentSpec {
  model?: string               // registered model id (null = root default)
  systemPrompt?: string
  maxSteps?: number
  allowedTools?: List<String>  // null = inherit all tools
  approvals?: "auto" | "deny"  // per-agent policy (default root)
  parallel?: boolean           // handled by parent loop today
}
```

Child run (`SubagentService`) becomes:

```
scope = root.extend(meta: {agent: specId})
        .intercept("agentLoop", loopConfigFor(spec))     // per-agent loop config
        .intercept("llm", llmConfigFor(spec))            // default model override
scope.plugin(agentLoopPlugin?, spec)                     // scoped loop instance
scope.plugin(toolViewPlugin?, spec.allowedTools)         // scoped allowlist registry view
... runTurn on the child session within scope ...
finally scope.fiber().disposeAsync()
```

Where the durable spine stays **shared**: session store/service, projections,
the event bus, skill registry and credentials are looked up from the root —
child sessions write into the same log the UI already reads. Only the
model/tools/loop/interaction *policy views* are scoped.

`runTurn` today already separates the configurable parts (model override +
prompt); the milestone formalizes it as a spec and routes it through an
actual subtree so config interception is structural rather than
argument-passed.

## Interaction routing (hard part)

Approvals / ask-user currently route to one front seat (`PendingInteractions`
per web instance). Under concurrent scopes, a child's tool call can ask for
approval while the parent waits — the UI needs to know *which* delegation is
asking.

Design: give each agent scope an `agentId`; interaction requests carry
`(agentId, request)`; the SSE relay emits an `agent` field on approval frames;
the rail renders a nested card ("subagent `web-demo` requests…"). CLI/headless
keep auto-accept unless `approvals: "deny"` on the spec. This is a separate
slice (M-C2) because it touches `InteractionService`, WebMain SSE frames and
the approval feature — wire types regenerate.

## Milestone slices

- **M-C1 — scoped loop via context subtree.** `AgentSpec` +
  `SubagentService.delegateSpec(task, spec)` builds an `extend()`-based scope
  with intercepted `agentLoop`/`llm` config and runs the child turn there.
  Existing tool/registry behavior unchanged; keep the 5-arg `runTurn` path as
  the root-context fast path. Verify: seam test that two delegations with
  different specs run with distinct model/system prompt headers (already
  covered per-turn) plus spec-driven `maxSteps`/`allowedTools` honored.
- **M-C2 — interaction routing by agent.** `agentId` through
  approvals/questions; SSE + rail updates; spec `approvals` policy.
- **M-C3 — allowlist & policy views.** A scoped tool registry wrapping the
  root one (spec `allowedTools`), enforced at `tools.execute`; option to
  reject unknown tools in child rounds with structured errors.
- **M-C4 (optional later) — true plugin islands per child** if a child ever
  needs its own profile rows: mount a `HarnessBoot`-lite loader on the child
  context with its own profile rows and isolated classloaders; high risk —
  needs epoch/disposal audits before committing.

## Concurrency & durability notes

- Sibling child turns run on virtual threads (already the mechanism behind
  `parallelDelegates`); event appends are per-session and the stores are
  synchronized, but projections/broadcast ordering across sessions is already
  tolerated (watermarks are per session).
- `AgentLoopService.delegates` executor is shared; each *scope* must not
  re-enter the same root turn (depth guard stays).
- Web `turnLock` serializes *user* turns per instance; scoped children are
  inside one user turn and bounded by the parent's tool round — no extra lock.

## Verification plan

- Seam tests (offline, deterministic mock): two spec-different children on
  one parent fan-out; allowedTools enforcement; maxSteps cap honored; dispose
  runs after scope end (registrations rolled back; recent-runs log shows
  done).
- Web smoke (web-mock + cue mock): parent prompt fan-out → child approvals
  carry agent ids in SSE → rail allows per child → answer assembled.
- Docs: agent-context + CHANGELOG updates.

## Open questions

- Should `AgentSpec` come from the model (tool arguments) only, or also from
  a host-side policy (profile)? Answer: both — tool args are the *request*,
  host policy clamps them (model/tool allowlists, max steps) before use.
- Per-child approval UX depth: nested rail card vs. collapsed count — pick in
  M-C2 with the web parity table.
- Executor sizing: virtual threads today; a semaphore bound per server may be
  wanted later for abusive fan-out.
