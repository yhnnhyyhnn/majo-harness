# majo-harness Architecture

English | [中文](architecture.zh-CN.md)

Read this before changing code under any `majo-*` module. It assumes you know Cordis/jcordis terms (`Context`, `Fiber`, `effect`, `waterfall`, loader entries); the jcordis README and examples are the reference.

## What majo-harness is

majo-harness is an **all-plugin agent harness**: every product capability is a plugin contributing services, events, and reversible effects to a shared jcordis `Context` tree. It mirrors the deepseek-harness (dsh) decomposition over cordis:

| dsh (TypeScript/cordis) | jcordis mechanism | majo-harness (Java) |
|---|---|---|
| profile/preset YAML composes the plugin tree | loader Entry tree + `Include`/config parsing + dependency epochs | `majo-boot.HarnessBoot` boots entries from profile YAML |
| package `core/session` — append-only `SessionEvent` log | `Service` + fiber effects | `majo-session` (`ctx.sessions`) |
| package `core/tools` — registry + guarded execution | events / waterfalls | `majo-tools` (`ctx.tools`) |
| package `llm/llm` — vocabulary + adapter seam | `Service` registry | `majo-llm` (`ctx.llm`) + provider plugins (`majo-llm` mock today, `majo-provider-*` next) |
| package `core/agent-loop` — default driver | plugin with declared injections | `majo-agent-loop` (`ctx.agentLoop`) |
| package `boot/app-boot` — profile glue | loader builtins registration | `majo-boot` |
| shipped profiles (`web`, `headless`, …) | profile files + app plugins | `majo-headless` + `headless.yml` |

There is deliberately **no privileged core module** in M1: like dsh's independent packages under `packages/core/`, each capability owns its interfaces next to its implementation and plugin, and consumers (the agent loop, the boot) depend on those modules only through their service seams. If a neutral "API spine" module ever becomes justified (typed event dictionary shared across modules), extract it the same way.

## Repository layout

```
majo-session/     session log: SessionEventType / SessionEvent / SessionStore
                  (InMemory + JSONL FileSessionStore) / SessionService / SessionPlugin
majo-tools/       ToolCall / ToolResult / ToolSpec / Tool / ToolRegistry / ToolEvents / ToolsPlugin
majo-llm/         ChatRole / ChatMessage / ChatRequest / ChatResponse / ChatModel / LLMService
                  / LLMServicePlugin / MockChatModel / MockLLMPlugin
majo-agent-loop/  MessageDeriver / AgentLoopService / AgentLoopPlugin
majo-boot/        HarnessBoot (builtins registration, profile parsing, launch)
majo-headless/    HeadlessMain / CalculatorTool / CalculatorToolPlugin / RunnerPlugin / headless.yml
docs/             this document (EN + zh-CN)
```

## ctx keys and the event dictionary

| ctx key | provided by plugin | type | owns |
|---|---|---|---|
| `sessions` | `session` | `SessionService` | create/append/read the durable log; broadcasts `session/event` |
| `tools` | `tools` | `ToolRegistry` | register tools (reversible), execute through the pipeline |
| `llm` | `llm` | `LLMService` | model registry + `complete()`; fires `llm/request`, `llm/response` |
| `agentLoop` | `agent-loop` | `AgentLoopService` | `runTurn(sessionId, userText)` |

| event | kind | args | semantics |
|---|---|---|---|
| `session/event` | emit | `(SessionEvent)` | every durable append, live observers |
| `llm/request` | emit | `(ChatRequest, String model)` | before a completion |
| `llm/response` | emit | `(ChatRequest, ChatResponse, String model)` | after a completion |
| `tools/pre-execute` | waterfall | `(ToolCall, Tool)` | rewrite or reject a call; returning without `next()` rejects |
| `tools/post-execute` | waterfall | `(ToolCall, ToolResult)` | observe or transform the result |

Waterfall listeners MUST call `next()` to delegate (the jcordis convention). Events are the extension points: policy, approval, telemetry, and guard plugins attach here without importing the loop.

## Composition and lifecycle

A running harness is a loader entry tree booted from YAML rows. Every row names a plugin registered as a builtin (shipped set in `HarnessBoot`, app plugins added with `.register(name, plugin)`). Rows may carry `config`; entry/plugin `inject` declarations list service dependencies.

Activation follows jcordis dependency epochs:

1. **PENDING** — the loader created the entry fiber, but one declared injection is not yet provided by an ACTIVE fiber.
2. Each `Service` construction (`new SessionService(ctx, …)` inside a plugin body) provides the service; the loader notifies dependents and they load — in profile row order this is deterministic.
3. **ACTIVE** — the plugin body has run and its registrations are live.
4. A provider disappears (entry removed, fiber disposed): dependents **revert their effects and drop back to PENDING**; when the provider returns they auto-reactivate. `removingAProviderDeactivatesItsDependentsAndReactivationReturns` proves the cascade and the return.

Two effect rules make the tree safe to mutate:

- **Registrations are reversible.** A plugin body returns its disposables from `apply` (e.g. `return tools.register(tool)`); the fiber collects them and reverts in reverse order on unload. Tool and model registrations therefore never outlive their provider — do not register through a service's own `ctx`, which would bind the effect to the registry's fiber instead of the caller's.
- **Listeners revert with their plugin.** `ctx.on(...)` is itself a fiber effect, so observers never leak across a plugin reload.

Misconfiguration fails loud: an unknown profile row name is rejected at `HarnessBoot.launch` before any entry is created; duplicate tool/model/service registrations throw; a missing `maxSteps` convergence throws instead of looping forever.

## A turn

`runTurn(sessionId, userText)` (in `AgentLoopService`) is one turn. Sequence of durable events appended through `ctx.sessions`:

```text
TURN_START
USER_MESSAGE content=userText
  step 1..n (one model request per step):
ASSISTANT_MESSAGE content? toolCalls?      # logged verbatim
TOOL_RESULT    toolCallId, name, ok, content  # one per executed call
  ...repeat until the model answers without tool calls...
ASSISTANT_MESSAGE content=final
TURN_END
```

Per step the loop derives model history from the log (`MessageDeriver`), prepends the system message, offers every registered tool spec, completes the request through `ctx.llm`, logs the assistant round **exactly as it was model-visible**, then executes requested calls through `ctx.tools` (which routes `tools/pre-execute` → tool → `tools/post-execute`).

## Model-visible implies logged

Anything that reaches a model request must be reconstructable from the session log. The M1 loop enforces the shape: each request's history is derived solely from events, and each assistant round is logged before its tool calls execute. Two M1 boundaries are documented trade-offs, not exceptions to hide behind:

- The system message is config-derived (it lives in the profile), not a session event; a durable `request/header`-style event arrives with the typed-projection milestone.
- `SessionEvent.fields` is an open JSON map; typed projections over the log (mirroring dsh's merge-extensible `SessionEventMap`) are the next milestone before transcript UI or replay tooling depends on schemas.

## Conventions

- Services are `io.jcordis.core.service.Service` subclasses provided inside plugin bodies; a `Service` ctor registers itself on its fiber and doubles as an `EventFilter` scoped by isolation realm.
- Plugin classes implement `io.jcordis.core.registry.Plugin` and are registered as **instances** (`loader.builtin(name, new XxxPlugin())`). `Plugin.constructor(Class)` is for the class-plugin/`Initializable` pattern and does not run `Plugin.apply` — do not use it for these bodies.
- Declare service dependencies on the plugin (`inject()`) so readiness and cascade are the loader's job, not a manual ordering concern; profile rows stay minimal.
- Registered builtin names live as `NAME` constants on the plugin classes; ctx keys and event names as constants on the services/event holders, one home per fact.
- Fail loudly: throw at the earliest resolvable point; never silently skip a missing referent.
- Tests describe behavior. Every seam in M1 has a focused test plus the headless end-to-end that boots the shipped profile and asserts the durable event sequence, the model-visible request history, and the removal/re-activation cascade.

## Roadmap

- **M1 (done)** — all-plugin vertical slice: profile-driven boot, session log, tools pipeline, mock LLM, agent loop, removal cascade. No network.
- **M2** — real DeepSeek provider behind `ChatModel` (`majo-provider-deepseek`), tool-schema-driven prompt assembly, file-backed session store by default, typed session projections (`request/header` events).
- **M3** — capability seams one by one mirroring dsh: fs/shell/subprocess, sandbox and approval policy, skills, subagents, interaction (ask-user/approval), settings/credentials, session titles; each as a Service Definition + Provider + Consumer trio plus profile rows and e2e.
- **M4** — packaging & distribution: plugin jars loaded via jcordis loader SPI/HMR, profile/bundle layering and patches on top of `HarnessBoot`, CLI and SDK surfaces.
