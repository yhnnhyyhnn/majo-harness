# Usage guide

Quick start and feature walkthrough for majo-harness v0.7.0.

English | [中文](usage.zh-CN.md)

## Quick start

```bash
# build
mvn -B -ntp clean verify -DskipTests

# start the web UI (offline mock model)
java -jar majo-web/target/majo-web-0.7.0.jar

# or with a real model endpoint
java -jar majo-web/target/majo-web-0.7.0.jar --profile web

# → open http://127.0.0.1:8787
```

## Connecting a model

The shipped `web.yml` profile has two models:

- **mock** — offline, deterministic (for testing without a key)
- **kilo-free** — free tier via the OpenAI-compatible gateway at `api.kilo.ai`

To use your own endpoint (LM Studio, Ollama, vLLM, any OpenAI-compatible API),
add a row to your profile:

```yaml
- id: llm-my-model
  name: llm-openai
  config:
    name: my-model
    model: my-model-id
    baseUrl: http://localhost:1234/v1   # LM Studio / Ollama / etc
```

Select it in the header model picker or via `/model my-model`.

## The 20 tools

| tool | what it does |
|---|---|
| `calc` | Integer arithmetic (gated → approval card) |
| `read_file` / `write` via `read_file` | Read a file (gated) |
| `run_shell` / `run_command` | Shell/command execution (gated) |
| `run_background` | Background execution with `job_output/list/kill` |
| `web_search` / `web_fetch` | Web search and page fetching |
| `delegate_task` | Fan out a scoped child agent |
| `todo_write` | Replace the session todo list |
| `exit_plan_mode` | Submit a plan for approval |
| `schedule_create/list/delete` | Timer reminders |
| `run_code` | **PTC**: execute JavaScript in a Node.js process |
| `spill_read` | Retrieve a spilled oversized tool output |
| `workflow_run` | Execute a named workflow |
| `list_skills` / `load_skill` | Load skill instructions |

## Key features

### @file mentions

Type `@` in the composer to get file completion from the workspace.
Selecting a file injects its content as a durable context note — the model
reads it without a tool round-trip. Text-only (binary rejected), 64k chars.

### Workflows

Define multi-step orchestrations in `workflows/*.yml`:

```yaml
name: my-task
description: One-line description
steps:
  - id: fetch
    kind: delegate              # child agent with scoped tools
    prompt: "Read {{args.path}} and summarize."
    allowedTools: [read_file]
  - id: analyze
    kind: turn                  # model turn in the run's child session
    prompt: "Analyze: {{steps.fetch.output}}"
onFailure: abort
```

Then: `/workflow my-task {"path": "README.md"}` or let the model use
`workflow_run`.

### PTC (`run_code`)

Instead of N tool calls, write one JS program:

```json
{"code": "const d = [3,1,2]; d.sort(); console.log(JSON.stringify(d));"}
```

Executes in a fresh Node.js process (30s timeout). For computation, JSON
transformation, or any logic that would otherwise cost multiple LLM
round-trips.

### Context injection

The `context` plugin (mounted by default) injects workspace instructions
from `AGENTS.md` / `CLAUDE.md` and the current time as a durable context
note at the first turn of each session.

### Spill

Oversized tool results (> 16 KiB) are stored out-of-band with a locator.
The model sees a preview + `spill_read` instruction. Complements compaction
pruning (which handles older results in derived history).

### Session management

- `/compact` — compress session history into a durable summary
- `@file` — reference files without tool calls
- `/plan` — plan mode with approval card
- `/delegate <task>` — one-shot child delegation
- `/status` — harness counters
- Trajectory view — turn-grouped event ledger with durations

### MCP servers

Add external tools via MCP (stdio or Streamable HTTP):

```yaml
- id: mcp
  name: mcp
  config:
    servers:
      filesystem:
        command: npx
        args: ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
      remote:
        url: https://mcp.example.com/mcp
        headers:
          Authorization: Bearer ${MY_TOKEN}
```

Tools appear as `mcp__<server>__<tool>`. Approval, audit, and the tool
catalog apply automatically.

### Remote execution (SSH)

Point `fs` and `subprocess` at a remote host:

```yaml
- id: fs
  name: fs
  config:
    ssh:
      host: myserver
      user: deploy
```

File ops and commands execute on the remote host; the invariant "local path
access is never inferred from a remote path string" holds. Requires
passwordless SSH; POSIX remote hosts only.

## Safety model

- Sensitive tools (`calc`, `read_file`, `run_shell`, `run_command`,
  `web_search`, `web_fetch`, `workflow_run`) are **approval-gated** by
  default — the model calls them, you see an approval card and Allow/Deny.
- Session-level policy: `session.approval.<tool-id>` = `ask|never|auto`.
- Approval audit pairs (`APPROVAL_REQUESTED`/`APPROVAL_DECIDED`) persist in
  the session log for every gated call.
- MCP env/header values reference env-var **names** only — credentials
  never appear in profile files.
- MCP stdio children get a scrubbed environment (allowlist only).

## Gates

```bash
bash scripts/check.sh   # no-stdout + ESLint + vitest + full Maven verify
```

Every commit should pass these gates; CI runs the same set (plus live
probes as best-effort).
