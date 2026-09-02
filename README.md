# majo-harness

<p align="left">
  English | <a href="README.zh-CN.md">简体中文</a>
</p>

**majo-harness** is an all-plugin agent harness on [jcordis](https://github.com/jcordis/jcordis) (the Java 21 port of [Cordis](https://github.com/cordiverse/cordis)), modeled on the architecture of [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness).

Every product capability — the session log, the tool registry, the model adapter, the agent loop itself — is a plugin that contributes a service and events to a shared `Context`. There is no privileged core to patch: you extend the harness by mounting a plugin beside the others, and registrations are reversible effects that unwind when their plugin unloads.

## Modules (M1 vertical slice)

| Module | Owns | ctx key | Plugin rows |
|---|---|---|---|
| `majo-session` | durable session log: append-only `SessionEvent` (memory/JSONL-file store) | `ctx.sessions` | `session` |
| `majo-tools` | tool seam: `Tool` / `ToolSpec` / `ToolCall` / `ToolResult` + guarded execution pipeline (`tools/pre-execute`, `tools/post-execute` waterfalls) | `ctx.tools` | `tools` |
| `majo-llm` | model vocabulary + `ChatModel` adapter seam + registry + deterministic mock provider | `ctx.llm` | `llm`, `llm-mock` |
| `majo-agent-loop` | default turn driver: derives model history from the session log and drives tool rounds | `ctx.agentLoop` | `agent-loop` |
| `majo-provider-openai` | OpenAI-compatible `ChatModel` provider — bring your own endpoint (LM Studio / Ollama / vLLM / gateway); `apiKey` optional | registers on `ctx.llm` | `llm-openai` |
| `majo-fs` | filesystem capability seam: `ctx.fs` over a swappable `FsProvider`, `fs/*` policy events, `read_file` tool consumer | `ctx.fs` (+ `ctx.tools`) | `fs`, `fs-tools` |
| `majo-subprocess` | subprocess capability seam: `ctx.subprocess` over a swappable `SubprocessProvider` (argv only, no shell), `subprocess/pre-execute` policy event, `run_command` tool consumer | `ctx.subprocess` (+ `ctx.tools`) | `subprocess`, `subprocess-tools` |
| `majo-shell` | shell capability seam over subprocess: `ctx.shell` runs scripts via Strategy-selected shell families through a `ShellProvider` (Adapter over `ctx.subprocess`), `shell/pre-execute` policy event, `run_shell` tool consumer | `ctx.shell` (+ `ctx.tools`, `ctx.subprocess`) | `shell`, `shell-tools` |
| `majo-sandbox` | sandbox capability seam: `ctx.sandbox` wraps argv before spawning via swappable providers (identity default; Linux bwrap assembled), `sandbox/pre-confine` policy event; shell consumers apply it with `confine: true` | `ctx.sandbox` | `sandbox` |
| `majo-interaction` | interaction capability seam: `ctx.interactions` approval & ask-user over registered handlers (auto/deny/canned; queue for humans), `tool-approval` gate plugin on `tools/pre-execute` | `ctx.interactions` | `interactions`, `tool-approval` |
| `majo-skill` | skill capability seam: `ctx.skills` aggregates skill providers (local `SKILL.md` directory provider shipped), `list_skills`/`load_skill` tool consumers | `ctx.skills` (+ `ctx.tools`) | `skills`, `skill-files`, `skill-tools` |
| `majo-subagent` | subagent capability seam: `ctx.subagent` delegates a task to a child session driven by the agent loop (depth-guarded), `delegate_task` tool consumer | `ctx.subagent` (+ `ctx.tools`) | `subagent`, `subagent-tools` |
| `majo-settings` | user settings: `ctx.settings` key/value store with optional JSON file provider | `ctx.settings` | `settings` |
| `majo-credentials` | credentials: `ctx.credentials` resolves secrets through providers (env + `.env` file shipped), values never logged | `ctx.credentials` | `credentials` |
| `majo-title` | session titles: `ctx.sessionTitle` holds the sole title provider (heuristic shipped), derived from the session log | `ctx.sessionTitle` | `session-title`, `session-title-heuristic` |
| `majo-boot` | profile-to-loader glue: ships plugins as builtins and boots an entry tree from YAML profiles | — | — |
| `majo-headless` | one-shot headless app: sample `calc` tool, `run` entry, `headless.yml` profile, e2e tests | — | `calc`, `run` |

Docs: [architecture](docs/architecture.md) · [中文架构](docs/architecture.zh-CN.md)

The shipped plugins are already registered as loader builtins by `majo-boot.HarnessBoot` (`session`, `session-projections`, `tools`, `llm`, `llm-mock`, `llm-openai`, `fs`, `fs-tools`, `subprocess`, `subprocess-tools`, `shell`, `shell-tools`, `sandbox`, `interactions`, `tool-approval`, `skills`, `skill-files`, `skill-tools`, `subagent`, `subagent-tools`, `settings`, `credentials`, `session-title`, `session-title-heuristic`, `agent-loop`). A profile picks the rows it needs — for example, adding file reads to a run:

```yaml
- id: fs
  name: fs
- id: fs-tools
  name: fs-tools      # registers the read_file tool on ctx.tools
```

## Requirements

- JDK 21
- Maven 3.x
- `io.jcordis:jcordis-all:1.0.0` in the local Maven repository

## Quick start

```bash
mvn test                     # unit + integration tests (mock model, no network)

mvn -DskipTests install      # install modules so the demo below can resolve them
mvn -pl majo-headless dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "majo-headless/target/classes;$(cat majo-headless/cp.txt)" \
     io.majo.harness.headless.HeadlessMain "1+2"
rm majo-headless/cp.txt
```

The boot prints the session transcript recorded by the plugin tree:

```text
== session 0e75e45e-... ==
  1 TURN_START {}
  2 USER_MESSAGE content="1+2"
  3 REQUEST_HEADER model="mock" tools=[calc] systemPrompt="You are a small calculator harness. Use the calc tool whenever asked for arithmetic."
  4 ASSISTANT_MESSAGE toolCalls=[{name=calc, arguments={"expression":"1+2"}, toolCallId=...}]
  5 TOOL_RESULT content="3"
  6 REQUEST_HEADER model="mock" tools=[calc] systemPrompt="You are a small calculator harness. Use the calc tool whenever asked for arithmetic."
  7 ASSISTANT_MESSAGE content="calculated: 3"
  8 TURN_END {}
answer: calculated: 3
```

The same run, fully driven by `majo-headless/src/main/resources/headless.yml`: every row is a plugin entry the loader activates when its injections are satisfied, in dependency order — no application code wires the loop together. Note the `REQUEST_HEADER` rows: every model request durably records its model, system prompt, and offered tool names, so the composition is always reconstructable from the log.

## Bring your own model endpoint

No key is required to run the harness: the deterministic mock needs no network, and the `llm-openai` provider speaks the OpenAI `chat/completions` wire protocol to any endpoint you choose (LM Studio, Ollama, vLLM, a One-API-style gateway, or a vendor with your own key). Replacing the model provider is a profile edit only — swap the mock rows for the provider rows and point `llm.defaultModel` at the registered name:

```yaml
- id: session
  name: session
- id: session-projections
  name: session-projections   # required by agent-loop
- id: llm
  name: llm
  config:
    defaultModel: local        # registry key of the provider below
- id: llm-openai
  name: llm-openai
  config:
    name: local
    model: your-model-id       # model id sent on the wire
    baseUrl: http://localhost:1234/v1   # e.g. LM Studio / Ollama / your gateway
    # apiKey: sk-...           # only when your endpoint requires one
```

The custom-provider path is covered offline by an end-to-end test that boots the tree against a local HTTP stub (`customOpenAiCompatibleProviderReplacesTheMock`).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
