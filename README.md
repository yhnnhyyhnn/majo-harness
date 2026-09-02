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
| `majo-boot` | profile-to-loader glue: ships plugins as builtins and boots an entry tree from YAML profiles | — | — |
| `majo-headless` | one-shot headless app: sample `calc` tool, `run` entry, `headless.yml` profile, e2e tests | — | `calc`, `run` |

Docs: [architecture](docs/architecture.md) · [中文架构](docs/architecture.zh-CN.md)

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
== session 2cff8adc-... ==
  1 TURN_START {}
  2 USER_MESSAGE content="1+2"
  3 ASSISTANT_MESSAGE toolCalls=[{arguments={"expression":"1+2"}, toolCallId=..., name=calc}]
  4 TOOL_RESULT content="3"
  5 ASSISTANT_MESSAGE content="calculated: 3"
  6 TURN_END {}
answer: calculated: 3
```

The same run, fully driven by `majo-headless/src/main/resources/headless.yml`: every row is a plugin entry the loader activates when its injections are satisfied, in dependency order — no application code wires the loop together.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
