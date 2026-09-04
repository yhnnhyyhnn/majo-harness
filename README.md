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
| `majo-web-access` | web access capability family (dsh `web/`): `ctx.web` with swappable search/fetch providers, anonymous HTTP fetch backend, static + real no-key Wikipedia search backends, `web_search`/`web_fetch` tools | `ctx.web` (+ `ctx.tools`) | `web`, `web-tools`, `web-fetch-http`, `web-search-static`, `web-search-wiki` |
| `majo-title` | session titles: `ctx.sessionTitle` holds the sole title provider (heuristic shipped), derived from the session log | `ctx.sessionTitle` | `session-title`, `session-title-heuristic` |
| `majo-boot` | profile-to-loader glue: ships plugins as builtins and boots an entry tree from YAML profiles | — | — |
| `majo-headless` | one-shot headless app: sample `calc` tool, `run` entry, `headless.yml` profile, e2e tests | — | `calc`, `run` |
| `majo-cli` | executable dsh-style launcher (shaded jar): `majo "task" [--profile …]`, prints the transcript, exit codes | — | — |
| `majo-web` | web profile app (dsh-style chat UI): JDK HTTP server over the booted tree, static session/conversation page + JSON turn API | — | (app) |

Docs: [architecture](docs/architecture.md) · [中文架构](docs/architecture.zh-CN.md) · [web 对齐](docs/web-parity.md) · [plugin development](docs/plugin-development.md) · [中文插件开发](docs/plugin-development.zh-CN.md)

## App entries (one harness, several entries)

```
能力库(jar):  majo-session / tools / llm / agent-loop / fs / shell / sandbox /
             interaction / skill / subagent / settings / credentials / title / …
组装层:      majo-boot.HarnessBoot（出厂 builtins + profile 解析/launch）
入口:        majo-cli → majo "task"      headless 一次性 runner（转写输出）
             majo-web → java -jar …jar    Web App：JSON/SSE API + React 页面托管
             HeadlessMain                 demo（mvn exec）
前端源码:    web-ui（React/Vite；产物拷入 majo-web 的 resources/static）
```

`majo-web` is the browser-facing entry (it both runs the backend service over a booted plugin tree and serves the compiled React UI). `majo-cli` is the script/one-shot entry. They share the same composable plugin tree — there is no privileged core.

The shipped plugins are already registered as loader builtins by `majo-boot.HarnessBoot` (`session`, `session-projections`, `tools`, `llm`, `llm-mock`, `llm-openai`, `fs`, `fs-tools`, `subprocess`, `subprocess-tools`, `shell`, `shell-tools`, `sandbox`, `interactions`, `tool-approval`, `skills`, `skill-files`, `skill-tools`, `subagent`, `subagent-tools`, `settings`, `credentials`, `session-title`, `session-title-heuristic`, `web`, `web-tools`, `web-fetch-http`, `web-search-static`, `web-search-wiki`, `agent-loop`). A profile picks the rows it needs — for example, adding file reads to a run:

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

## Quick start (launch like a CLI, dsh-style)

```bash
mvn -DskipTests install                       # build & install the reactor
java -jar majo-cli/target/majo-cli-0.1.0-SNAPSHOT.jar "1+2"
```

The launcher boots the built-in `headless` profile (all shipped plugins via profile rows) and runs one task — no API key needed, the deterministic mock model drives the tool turn:

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

```text
majo --profiles                                # list built-in profiles
majo --profile ./my-profile.yml "task"        # any YAML file of builtin rows
majo --plugin ext=./ext-plugin.jar "task"     # mount an external plugin jar
majo "task"                                   # = --profile headless
```

The `run` row is appended automatically when absent; swap the model provider in a copied profile to point at your own endpoint (below). `REQUEST_HEADER` rows record each request's model/system prompt/tool names durably. During development you can also run `mvn -pl majo-headless exec:java -Dexec.args="1+2"`.

External plugin jars follow the jcordis contract: an SPI manifest `META-INF/services/io.jcordis.core.registry.Plugin` and an isolated class loader. Mount one with `--plugin name=./path.jar` and reference `name` from profile rows; hot replacement (`replaceJar`) and unload go through `HarnessBoot.loader()`. The `PluginJarTest` in majo-boot is a ready recipe for building such a jar.

## Web UI (React, dsh-style)

```bash
mvn -DskipTests install
java -jar majo-web/target/majo-web-0.1.0-SNAPSHOT.jar          # http://localhost:8787 (web.yml)
java -jar majo-web/target/majo-web-0.1.0-SNAPSHOT.jar --profile web-mock   # offline (mock llm)
```

Open http://localhost:8787: a session sidebar (rename ✎ / delete ✕ per row), user/tool/assistant bubbles, a composer, and collapsible sidebar sections (Skills / Settings / Subagents) that fill registration-only slots. The header carries two model pickers — the global model and a per-session override — and assistant messages accept 👍/👎 feedback (persisted) plus ⧉ copy on user/tool/assistant text. Slash commands work in the composer (`/help`, `/clear`, `/new`, `/model`, `/session-model`). The app is a React/Vite TypeScript app under `web-ui/`, whose compiled assets are committed into `majo-web/src/main/resources/static` and served by the Java backend. Its wire types are generated from the Java contract: edit `WebApiModels`/`SessionEventType`, run `bash scripts/gen-web-types.sh`, then rebuild. UI builds happen through Maven (`mvn -pl web-ui generate-resources`, needs npm). Tool results ship structured `data` on the wire (exit codes, web hits, child session ids…) so result cards render without re-parsing text — `delegate_task` cards even link straight to their child transcript.

The shipped `web.yml` points at the kilo free tier over the OpenAI-compatible gateway - **no API key required** (free tier can occasionally return upstream 502s; retry). `web.yml` also mounts the real no-key Wikipedia search backend (`web-search-wiki`) and two sample skills from the repo `skills/` directory (`summarize`, `check-style`); `web-mock.yml` keeps the same panels working fully offline with the deterministic mock. Sessions, renames, model choices (global + per-session) and message ratings persist across restarts under `~/.majo-harness/web/` (JSONL session files + `settings.json`); point the JVM at another `user.home` to isolate a demo. 

JSON API for other clients:

```bash
curl -X POST -H 'Content-Type: application/json' -d '{"task":"1+2"}' http://localhost:8787/api/turn
curl http://localhost:8787/api/sessions
curl http://localhost:8787/api/sessions/<id>
curl -X PUT -H 'Content-Type: application/json' -d '{"title":"My notes"}' http://localhost:8787/api/sessions/<id>/title
curl -X DELETE http://localhost:8787/api/sessions/<id>
curl http://localhost:8787/api/skills
curl http://localhost:8787/api/subagents
curl http://localhost:8787/api/info
curl "http://localhost:8787/api/sessions/<id>/events?since=0"
curl -X PUT -H 'Content-Type: application/json' -d '{"model":"mock"}' http://localhost:8787/api/sessions/<id>/model
curl -X DELETE http://localhost:8787/api/sessions/<id>/model
curl -X PUT -H 'Content-Type: application/json' -d '{"value":"up"}' http://localhost:8787/api/messages/<id>/<seq>/feedback
curl http://localhost:8787/api/sessions/<id>/feedback
```

Troubleshooting: if the page stays blank, an older instance is usually still holding port 8787 — the new process exits with a clear "port … is already in use" message while the browser keeps talking to the stale one. Stop old `java` processes or pick another port (`--port 9000`), then hard-refresh (Ctrl+F5). The page shows a visible "offline" banner instead of failing silently when the backend cannot be reached.

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
