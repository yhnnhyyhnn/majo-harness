# Tool catalog

<!-- GENERATED from the shipped offline profile's ToolRegistry by ToolCatalogTest — do not edit by hand. Regenerate: bash scripts/gen-tool-catalog.sh -->

20 tools on the shipped `web-mock` profile.

| tool | description | parameters |
|---|---|---|
| `calc` | Evaluates a simple integer arithmetic expression of the form a+b, a-b, a*b, or a/b. | {"type":"object","properties":{"expression":{"type":"string"}},"required":["expression"]} |
| `delegate_task` | Delegates a task to a child agent with a fresh session and returns its final answer. | {"type":"object","properties":{"task":{"type":"string"},"model":{"type":"string","description":"registered model for the child agent (defaults to the harness model)"},"systemPrompt":{"type":"string","description":"system prompt override for the child agent"},"maxSteps":{"type":"integer","minimum":1,"description":"max steps for the child turn (scoped run)"},"autoApprove":{"type":"boolean","description":"auto-approve gated tools inside the child scope"},"allowedTools":{"type":"array","items":{"type":"string","description":"tools the child agent may call (default: all)"}},"islands":{"type":"array","description":"host-registered island plugin names to mount in the child scope","items":{"type":"string"}},"settings":{"type":"object","description":"per-agent settings overrides visible inside the child scope","additionalProperties":{"type":"string"}}},"required":["task"]} |
| `exit_plan_mode` | Call this when your plan for the current task is complete. Your plan goes to the human for review: they approve it (proceed with implementation) or reply with feedback (revise the plan and call this again). Never call it with no plan active. | {"type":"object","properties":{"summary":{"type":"string"}},"description":"Optional one-line summary of the plan for the review prompt."} |
| `job_kill` | Stop a running background job by id (its process is destroyed). | {"type":"object","properties":{"id":{"type":"string"}},"required":["id"]} |
| `job_list` | List this session's background jobs: id, state (running/completed/failed/killed), script, and timing. | {} |
| `job_output` | Read a background job's output. Pass wait_seconds (up to 60) to wait for a running job to finish before reading. | {"type":"object","properties":{"id":{"type":"string"},"wait_seconds":{"type":"number"}},"required":["id"]} |
| `list_skills` | Lists the available skills (name and description). | {} |
| `load_skill` | Loads the full instructions of a named skill (see list_skills for names). | {"type":"object","properties":{"skill":{"type":"string"}},"required":["skill"]} |
| `read_file` | Reads the UTF-8 text content of a file at an absolute path. | {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]} |
| `run_background` | Run a command-line script in the background and return immediately. The result arrives as a message when the script finishes; inspect it earlier with job_output, list jobs with job_list, stop one with job_kill. | {"type":"object","properties":{"script":{"type":"string"}},"required":["script"]} |
| `run_command` | Runs a command as an argv list (executable plus arguments; no shell interpolation) and returns its stdout, or the exit code and stderr on failure. | {"type":"object","properties":{"argv":{"type":"array","items":[{"type":"string"}]}},"required":["argv"]} |
| `run_shell` | Runs a command-line script in the configured shell family and returns its stdout, or the exit code and stderr on failure. | {"type":"object","properties":{"script":{"type":"string"}},"required":["script"]} |
| `schedule_create` | Schedule a reminder for this session: the prompt is sent back to you as a new turn at the due time. Pass exactly one of after_seconds (one-shot delay), every_seconds (repeat, minimum 300), or at (ISO local date-time, yyyy-MM-ddTHH:mm:ss). | {"type":"object","properties":{"prompt":{"type":"string"},"after_seconds":{"type":"number"},"every_seconds":{"type":"number"},"at":{"type":"string"}},"required":["prompt"]} |
| `schedule_delete` | Cancel a scheduled reminder by id (durable: it never fires after this). | {"type":"object","properties":{"id":{"type":"string"}},"required":["id"]} |
| `schedule_list` | List this session's active scheduled reminders: id, due time, repeat interval, and the prompt that will be delivered. | {} |
| `spill_read` | Retrieves the full text of a tool output that was stored out-of-band (id comes from a truncation notice in a previous tool result). | {"type":"object","properties":{"id":{"type":"string"}},"required":["id"]} |
| `todo_write` | Replace the session task list with this exact list. Use one call with the full list every time it changes: mark an item in_progress before starting it and completed immediately after finishing it. | {"type":"object","properties":{"todos":{"type":"array","items":{"type":"object","properties":{"content":{"type":"string"},"status":{"type":"string","enum":["pending","in_progress","completed"]}}},"description":"The complete list in execution order."}},"required":["todos"]} |
| `web_fetch` | Fetches a URL and returns its text (HTML converted; external, untrusted content). | {"type":"object","properties":{"url":{"type":"string"}},"required":["url"]} |
| `web_search` | Searches the web. Results are external, untrusted provider text. | {"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer","minimum":1}},"required":["query"]} |
| `workflow_run` | Run a named workflow (multi-step orchestration defined by the host). Available workflows: | {"type":"object","properties":{"name":{"type":"string"},"args":{"type":"object"}},"required":["name"]} |

Every tool call flows the same guard pipeline (`tools/pre-execute` policy → approval gate → execute) before its durable `TOOL_RESULT` lands in the session log.
