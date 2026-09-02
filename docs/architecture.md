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
| package `llm/llm` — vocabulary + adapter seam | `Service` registry | `majo-llm` (`ctx.llm`) + provider plugins (`majo-llm` mock today, `majo-provider-openai` bring-your-own-endpoint next) |
| fs capability (dsh `fs/` + tools) | service + waterfall policy events | `majo-fs` (`ctx.fs`, `fs/*` events, `read_file` tool) |
| subprocess capability (dsh `subprocess/`) | service + waterfall policy event + tool | `majo-subprocess` (`ctx.subprocess`, argv only, `run_command` tool) |
| shell capability (dsh `shell/`) | service over subprocess + strategy-selected families | `majo-shell` (`ctx.shell`, `run_shell` tool) |
| sandbox capability (dsh `sandbox/`) | argv-wrap provider seam + policy waterfall | `majo-sandbox` (`ctx.sandbox`, identity/bwrap providers) |
| interaction capability (dsh `interaction/`) | approval/ask-user handlers + tool gate | `majo-interaction` (`ctx.interactions`, `tool-approval`) |
| skill capability (dsh `skill/`) | provider registry + local provider + catalog/loader tools | `majo-skill` (`ctx.skills`, `list_skills`/`load_skill`) |
| subagent capability (dsh `subagent/`) | child-session delegation + depth guard | `majo-subagent` (`ctx.subagent`, `delegate_task`) |
| settings capability (dsh `settings/`) | user-settings store + file provider | `majo-settings` (`ctx.settings`) |
| credentials capability (dsh `credentials/`) | secret resolution + env/.env provider | `majo-credentials` (`ctx.credentials`) |
| session-title capability (dsh `session-title/`) | sole provider + heuristic default | `majo-title` (`ctx.sessionTitle`) |
| package `core/agent-loop` — default driver | plugin with declared injections | `majo-agent-loop` (`ctx.agentLoop`) |
| package `boot/app-boot` — profile glue | loader builtins registration | `majo-boot` |
| shipped profiles (`web`, `headless`, …) | profile files + app plugins | `majo-headless` + `headless.yml` |

Model providers are swappable behind `ctx.llm` by profile row alone. The shipped set is `llm-mock` (deterministic, offline) and `llm-openai` (an OpenAI `chat/completions` `ChatModel` for any endpoint: LM Studio, Ollama, vLLM, a gateway, or a vendor keyed via `apiKey` — so running the harness never requires a particular vendor key). Point `llm.defaultModel` at the registry key the provider registers (`name`, defaulting to `model`) and the agent loop is none the wiser.

The fs capability follows the same trio: `FileSystemService` (`ctx.fs`) runs every operation through `fs/*` waterfalls where policy and observability plugins attach (they reject by throwing `FsException`); the `LocalFsProvider` implements the provider seam; `fs-tools` registers the `read_file` tool consumer on `ctx.tools`. A profile adds the capability with two rows.

Subprocess is the second process-world seam: `SubprocessService` (`ctx.subprocess`) executes argv lists (never a shell command line — no interpolation happens here) through the `subprocess/pre-execute` waterfall; `LocalSubprocessProvider` drains output on virtual threads and enforces the timeout by destroy; a command without an explicit timeout is resolved against the service's configured `defaultTimeoutSeconds` (the explicit resolve step); `subprocess-tools` registers the `run_command` consumer.

Shell layers on top of subprocess: `ShellService` (`ctx.shell`) is a facade that runs command-line scripts through `shell/pre-execute`; a `ShellLauncher` strategy (chosen by `ShellFamily` factory: bash/PowerShell/cmd, platform default, config-overridable) turns the script into argv; `LocalShellProvider` adapts that argv to `ctx.subprocess`; `run_shell` is the tool consumer. Command-line syntax enters here and here only.

Sandbox wraps process spawning: `SandboxService` (`ctx.sandbox`) confines an argv list through a swappable `SandboxProvider` (identity default — real confinement is a provider swap; Linux can assemble `bwrap` argv) behind the `sandbox/pre-confine` waterfall. Spawner consumers apply the wrap: the shell provider confines its argv before spawning when its row sets `confine: true` (which requires the sandbox row). Confinement never silently degrades: an unknown provider, blank bwrap options, or a missing sandbox row behind `confine: true` all fail loudly.

Interaction gates operations behind humans: `InteractionService` (`ctx.interactions`) routes approval and ask-user requests to registered `InteractionHandler` strategies in order; handlers abstain by default and an unanswered approval denies while an unanswered question fails loudly (fail-safe). Shipped handler modes are `auto`/`deny` for approvals and `canned:`/none for answers; a `QueueingInteractionHandler` provides the interactive channel for a UI. The `tool-approval` plugin is a Chain-of-Responsibility listener on `tools/pre-execute`: gated tools pause behind `ctx.interactions` and delegate only on approval.

Skills give the model reusable procedures: `SkillRegistry` (`ctx.skills`) aggregates `SkillProvider` contributions — the shipped `skill-files` provider scans directories of `SKILL.md` files (front-matter descriptions, body instructions) — and rejects name collisions across providers loudly. `list_skills`/`load_skill` browse the catalog and load instructions as tool results; wiring loaded skills into the prompt assembly arrives with the system-prompt seam.

Subagents delegate within the same tree: `SubagentService` (`ctx.subagent`) opens a fresh child session and drives it through the same `ctx.agentLoop`, returning the child's final text (a child agent is a new session with isolated history, not a second loop). Nesting depth is config-guarded (`maxDepth`, default 3) and exceeding it fails loudly; the `delegate_task` tool exposes delegation to the model.

Settings and credentials give hosts and providers their configuration without hardcoding: `SettingsService` (`ctx.settings`) is a validated string key/value store whose optional `path` enables the JSON file provider (write-through after every set, atomic replace, loud on corrupt files). `CredentialsService` (`ctx.credentials`) resolves secrets through registered `CredentialProvider`s in order; the shipped env provider merges `.env` (KEY=VALUE, comments, quotes) under real environment variables. Credential values never enter exceptions or messages.

Session titles round out the log spine: `SessionTitleService` (`ctx.sessionTitle`) holds the **sole** registered `SessionTitleProvider` (duplicates fail loudly) and derives a session's title from its durable events; the heuristic provider titles from the first user message (whitespace-collapsed, truncated), and an LLM-backed provider swaps in behind the same seam. Titles may be `null` until a session has content worth titling.

There is deliberately **no privileged core module** in M1: like dsh's independent packages under `packages/core/`, each capability owns its interfaces next to its implementation and plugin, and consumers (the agent loop, the boot) depend on those modules only through their service seams. If a neutral "API spine" module ever becomes justified (typed event dictionary shared across modules), extract it the same way.

## Repository layout

```
majo-session/     session log: SessionEventType / SessionEvent / SessionStore
                  (InMemory + JSONL FileSessionStore) / SessionService / SessionPlugin
                  + typed view (TypedSessionEvent) and projection seam
                  (SessionProjection / SessionProjections / SessionProjectionsPlugin)
majo-tools/       ToolCall / ToolResult / ToolSpec / Tool / ToolRegistry / ToolEvents / ToolsPlugin
majo-llm/         ChatRole / ChatMessage / ChatRequest / ChatResponse / ChatModel / LLMService
                  / LLMServicePlugin / MockChatModel / MockLLMPlugin
majo-agent-loop/  MessageDeriver / AgentLoopService / AgentLoopPlugin
majo-provider-openai/  OpenAiChatModel / OpenAiProviderPlugin (OpenAI-compatible endpoint)
majo-fs/          FsProvider + LocalFsProvider / FileSystemService / FsPlugin / FsToolPlugin
                  / ReadFileTool (fs capability seam)
majo-subprocess/  Command / ProcessResult / SubprocessProvider + LocalSubprocessProvider
                  / SubprocessService / SubprocessPlugin / SubprocessToolPlugin / RunCommandTool
majo-shell/       ShellCommand / ShellResult / ShellProvider / LocalShellProvider (Adapter)
                  / ShellLauncher + ShellFamily (Strategy/Factory) / ShellService / RunShellTool
majo-sandbox/     SandboxProvider (Strategy) + IdentitySandboxProvider / BwrapSandboxProvider
                  / SandboxService / SandboxPlugin (Factory)
majo-interaction/ ApprovalRequest/Question/ApprovalDecision / InteractionHandler (Strategy)
                  / InteractionService / InteractionPlugin / QueueingInteractionHandler
                  / ToolApprovalPlugin (tool-approval gate on tools/pre-execute)
majo-skill/       Skill / SkillProvider seam / FileSkillProvider (SKILL.md dirs)
                  / SkillRegistry / SkillPlugin / FileSkillPlugin
                  / ListSkillsTool / LoadSkillTool / SkillToolsPlugin
majo-subagent/    SubagentService / SubagentPlugin / DelegateTaskTool / SubagentToolPlugin
majo-settings/    SettingsService / SettingsPlugin (JSON file provider)
majo-credentials/ CredentialProvider seam + EnvCredentialProvider (.env parse)
                  / CredentialsService / CredentialsPlugin
majo-title/       SessionTitleProvider seam + HeuristicSessionTitleProvider
                  / SessionTitleService / SessionTitlePlugin / HeuristicTitlePlugin
majo-util/        Disposables (composite disposer factory)
majo-boot/        HarnessBoot (builtins registration, profile parsing, launch)
majo-headless/    HeadlessMain / CalculatorTool / CalculatorToolPlugin / RunnerPlugin / headless.yml
                  / TranscriptPrinter (shared transcript rendering)
majo-cli/         MajoCli (dsh-style launcher, shaded executable jar)
docs/             this document (EN + zh-CN)
```

## ctx keys and the event dictionary

| ctx key | provided by plugin | type | owns |
|---|---|---|---|
| `sessions` | `session` | `SessionService` | create/append/read the durable log; broadcasts `session/event` |
| `tools` | `tools` | `ToolRegistry` | register tools (reversible), execute through the pipeline |
| `llm` | `llm` | `LLMService` | model registry + `complete()`; fires `llm/request`, `llm/response` |
| `agentLoop` | `agent-loop` | `AgentLoopService` | `runTurn(sessionId, userText)` |
| `sessionProjections` | `session-projections` | `SessionProjections` | registered units fold committed events; hosts read typed state |
| `subprocess` | `subprocess` | `SubprocessService` | runs argv commands through `subprocess/pre-execute` |
| `shell` | `shell` | `ShellService` | runs scripts through `shell/pre-execute` over a strategy shell |
| `sandbox` | `sandbox` | `SandboxService` | confines argv through `sandbox/pre-confine` before spawning |
| `interactions` | `interactions` | `InteractionService` | routes approvals and questions to registered handlers |
| `skills` | `skills` | `SkillRegistry` | aggregates skill providers; load by name |
| `subagent` | `subagent` | `SubagentService` | delegates tasks to depth-guarded child sessions |
| `settings` | `settings` | `SettingsService` | validated key/value store (JSON file-backed when configured) |
| `credentials` | `credentials` | `CredentialsService` | resolves secrets through registered providers |
| `sessionTitle` | `session-title` | `SessionTitleService` | derives titles via the sole registered provider |
| `fs` | `fs` | `FileSystemService` | text read/write/glob through `fs/*` waterfalls |

| event | kind | args | semantics |
|---|---|---|---|
| `session/event` | emit | `(String sessionId, SessionEvent)` | every durable append, live observers |
| `llm/request` | emit | `(ChatRequest, String model)` | before a completion |
| `llm/response` | emit | `(ChatRequest, ChatResponse, String model)` | after a completion |
| `tools/pre-execute` | waterfall | `(ToolCall, Tool)` | rewrite or reject a call; returning without `next()` rejects |
| `tools/post-execute` | waterfall | `(ToolCall, ToolResult)` | observe or transform the result |
| `fs/read` `fs/write` `fs/glob` | waterfall | `(path…)` | per-operation policy/observability; throwing `FsException` rejects |
| `subprocess/pre-execute` | waterfall | `(Command)` | policy before every run; throwing `SubprocessException` rejects |
| `shell/pre-execute` | waterfall | `(ShellCommand)` | policy before every run; throwing `ShellException` rejects |
| `sandbox/pre-confine` | waterfall | `(List<String> argv)` | policy before confinement; throwing `SandboxException` rejects |
| `interaction/approval-request` / `interaction/approval` | emit | `(ApprovalRequest)` / `(ApprovalRequest, ApprovalDecision)` | approval entry & resolution, live observers |
| `interaction/question` / `interaction/answer` | emit | `(Question)` / `(Question, String)` | ask-user entry & resolution |

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

## Typed session events and projections

`SessionEvent.fields` stays the durable, open wire format, but consumers no longer need to read it stringly. {@code TypedSessionEvent.of(event)} parses every kind into a closed sealed record (user text, assistant rounds with their serialized tool calls, tool results, request headers), failing loudly on malformed payloads.

`ctx.sessionProjections` mirrors dsh's projection seam. Contributors register a {@code SessionProjection} unit (returning the disposer so it reverts on unload); the registry feeds units from every `session/event` broadcast and from {@code replay(sessionId)}, deduplicating by a per-unit, per-session sequence watermark so late-attached units converge idempotently. Host consumers read typed state through the unit's concrete type and fail loudly when a required unit is absent. The agent loop contributes the {@code turnSummary} unit (open-turn/turn/round/tool counts plus last user and final texts); every agent-loop profile therefore includes the `session-projections` row.

```yaml
- id: session-projections
  name: session-projections   # typed projection seam; agent-loop contributes turnSummary
```

## A turn

`runTurn(sessionId, userText)` (in `AgentLoopService`) is one turn. Sequence of durable events appended through `ctx.sessions`:

```text
TURN_START
USER_MESSAGE content=userText
  step 1..n (one model request per step):
REQUEST_HEADER model, systemPrompt, toolNames   # logged ahead of the completion
ASSISTANT_MESSAGE content? toolCalls?      # logged verbatim
TOOL_RESULT    toolCallId, name, ok, content  # one per executed call
  ...repeat until the model answers without tool calls...
ASSISTANT_MESSAGE content=final
TURN_END
```

Per step the loop derives model history from the log (`MessageDeriver`), prepends the system message, offers every registered tool spec, logs the **request header** (the resolved model, the system prompt, and the offered tool names — so the composition is durable even when a completion fails), completes the request through `ctx.llm`, logs the assistant round **exactly as it was model-visible**, then executes requested calls through `ctx.tools` (which routes `tools/pre-execute` → tool → `tools/post-execute`).

## Model-visible implies logged

Anything that reaches a model request must be reconstructable from the session log. The loop enforces the shape: each request's history is derived solely from events, each request **header** (model, system prompt, offered tool names) is durable ahead of the completion, and each assistant round is logged before its tool calls execute.

Two M2 boundaries are documented trade-offs, not exceptions to hide behind:

- Tool *schemas* are offered by the live registry at request time; the header records their names only. Replaying a request needs the same tool registry mounted (identical plugin composition); snapshotting full schemas per header is deferred to typed projections.
- `SessionEvent.fields` is the open JSON wire format; the typed view ({@code TypedSessionEvent}) and the projection seam already exist for consumers, and writer-side typed builders are a later polish.

## Conventions

- Services are `io.jcordis.core.service.Service` subclasses provided inside plugin bodies; a `Service` ctor registers itself on its fiber and doubles as an `EventFilter` scoped by isolation realm.
- Plugin classes implement `io.jcordis.core.registry.Plugin` and are registered as **instances** (`loader.builtin(name, new XxxPlugin())`). `Plugin.constructor(Class)` is for the class-plugin/`Initializable` pattern and does not run `Plugin.apply` — do not use it for these bodies.
- Declare service dependencies on the plugin (`inject()`) so readiness and cascade are the loader's job, not a manual ordering concern; profile rows stay minimal.
- Registered builtin names live as `NAME` constants on the plugin classes; ctx keys and event names as constants on the services/event holders, one home per fact.
- Fail loudly: throw at the earliest resolvable point; never silently skip a missing referent.
- Tests describe behavior. Every seam in M1 has a focused test plus the headless end-to-end that boots the shipped profile and asserts the durable event sequence, the model-visible request history, and the removal/re-activation cascade.

## Design-pattern map

Patterns are used where they delete duplication or keep seams honest — never pattern-for-pattern's sake. Current map:

| Pattern | Where | Why it fits |
|---|---|---|
| Strategy | `ChatModel`/`FsProvider`/`SubprocessProvider`/`ShellProvider` provider seams; `ShellLauncher` shell families | a capability swappable behind one interface, chosen by profile row/config |
| Factory Method | `ShellFamily.detect/ofConfig` launchers; `HarnessBoot` plugin builtins | object creation localized where selection policy lives |
| Adapter | `LocalShellProvider` over `ctx.subprocess` | script→argv worlds map without leaking subprocess types into shell consumers |
| Facade | `ShellService`, `HarnessBoot` | narrow entry point over a richer subsystem |
| Composite | `Disposables` (`majo-util`) | multi-registration plugins return one reverting disposer |
| Observer | `session/event`, `llm/*`, waterfalls' listeners | registrations are fiber effects, so observers auto-revert on unload |
| Chain of Responsibility | `tools/pre-execute`, `fs/*`, `subprocess/pre-execute`, `shell/pre-execute` | each listener rewrites/rejects or delegates via `next()` |
| Template Method (planned) | sealed typed dispatch in `TypedSessionEvent` consumers | one traversal, kind-specific steps |
| Visitor-like (built-in) | sealed-interface switches (`TypedSessionEvent`, event enums) | exhaustive, compile-checked kind dispatch without instanceof chains |

Guiding rule: a new capability seam keeps this shape — Service (Facade) + Provider interface (Strategy) + policy waterfall (Chain of Responsibility) + tool consumer + Composite disposers returned from the contributing plugin.

## Roadmap

- **M1 (done)** — all-plugin vertical slice: profile-driven boot, session log, tools pipeline, mock LLM, agent loop, removal cascade. No network.
- **M2 (done)** — model-provider swaps behind `ctx.llm` (generic OpenAI-compatible provider `majo-provider-openai`, offline wire-tested against a local HTTP stub); durable request headers (`REQUEST_HEADER` events log model/system prompt/tool names per step, closing the M1 composition boundary); file session store default directory (`<user.home>/.majo-harness/sessions`) with memory stores for hermetic tests.
- **M3 (done)** — capability seams mirroring dsh, each a Service Definition + Provider + Consumer trio plus profile rows and e2e: filesystem, typed session projections, subprocess, shell, sandbox, interaction/approval, skills, subagents, settings/credentials, session titles. The system-prompt section-assembly seam (plugins contributing prompt sections that the loop folds into the system message) is deferred to M4 alongside prompt work.
- **M4 (in progress)** — distribution first step landed: the `majo-cli` shaded launcher boots a profile and runs one task, printing the transcript (`majo "task"`, `--profile`, exit codes). Remaining: plugin jars loaded via jcordis loader SPI/HMR, profile/bundle layering and patches on top of `HarnessBoot`, SDK surfaces; system-prompt section assembly; per-agent contexts for concurrent subagents.
