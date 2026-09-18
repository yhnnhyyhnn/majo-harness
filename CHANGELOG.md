# Changelog

All notable changes to majo-harness. Entry format: feature summary (English)
with 中文说明 where useful. Dates are best-effort (repo history is the
source of truth).

## [0.1.0] - 2026-09-06

### Dependency
- **jcordis 1.0.1-SNAPSHOT** (local repo): adopted agent-scope semantics —
  per-agent runs mount on an isolated child context via a plugin fiber
  (shadow registration + rollback), vendored libs/updated; waterfall
  `next()` is now single-use with defensive arg copies, so pre-execute
  rewriting was removed (observe/reject only).

First tagged release (git `v0.1.0`): full capability seams, web-parity UI, three-tier plugin model, and the agent-scoping milestone M-C1…C3 verified against a real model.

### Plugins & web serving
- **Three-tier plugin model** (后端 SPI / 托管页面 + postMessage 桥 / 原生
  `plugin.mjs` 运行时进槽)：`majo-web --plugin name=jar` hosts
  `static-web/<name>/` assets under `/plugins/<name>/…`, lists them via
  `GET /api/plugins` (title from `plugin.json`, module URL when present); the
  sidebar **Plugins** section opens hosted pages in a sandboxed pane and
  loads native modules (`register(host)` + rollback disposers through the
  runtime registrar). Example: `examples/web-plugin-demo` +
  `scripts/build-plugin-demo.sh`; guide:
  `docs/plugin-development(.zh-CN).md`. 插件页面可经 postMessage 驱动宿主
  （flash/newChat/close/sendTask/openSession）。原生模块可在 Plugins 面板
  **mount / reload（cache-bust 热重载 plugin.mjs）/ unload（disposer 回滚槽贡献）**；
  `.mjs` 按 `text/javascript` 伺服。
- **Web serving reliability**: JDK `HttpServer` serializes requests per
  keep-alive connection, which could hang Chrome's parallel module fetches —
  `WebMain` sends `Connection: close` on static/JSON responses (SSE stays
  open). `web-ui` vite dev proxies `/api` + `/plugins` to a backend
  (`MAJO_API_TARGET`).
- **UI boot diagnostics**: `#root` fallback marker + window-error /
  `onRecoverableError` surfacing written straight into the page.

### Capability / data plumbing
- `ToolResult` structured `data` seam: shell/command `{exitCode,stdout,
  stderr}`, `read_file {path}`, `web_search {hits}`, `web_fetch {url,title}`,
  `delegate_task {childSessionId}` — durably logged (`FIELD_DATA`), on the
  wire as `EventFrame.data`, rendered as rich tool cards; child links jump to
  the delegated transcript.
- Real no-key search backend `web-search-wiki` (lazy-mount); sample skills in
  repo `skills/` mounted by web profiles; subagent recent-run log surfaced
  (`GET /api/subagents`); per-session model override, `sinceSeq` incremental
  events (`GET …/events?since=`), durable web state under
  `~/.majo-harness/web/`.
- **Verified on a real model (kilo free tier):** a fan-out prompt produced two delegate_task calls in one tool round, children ran in parallel, per-child calc approvals surfaced over SSE tagged `subagent-<child8>`, and the parent assembled correct results. Scoped child agents (M-C1 seed): `AgentSpec` + `SubagentService.delegateSpec` runs a child turn on an isolated context subtree (fresh loop instance via a lightweight plugin, registration rolled back by the plugin fiber) with per-spec model/system prompt/max-steps; parallel `delegate_task` fan-out (`parallelDelegates`, on in web/web-mock profiles). 设计文档见 `docs/agent-context(.zh-CN).md`。
- Agent-scoped interaction routing (M-C2/C3): approvals/questions carry the originating agent label (SSE frames + rail tags); per-scope auto-approve and an allowed-tools whitelist (backstop on the PRE_EXECUTE chain) enforce spec policy.

### Web UI (dsh-style dark theme)
- Design tokens ported from deepseek-harness `ui-theme`; full session
  management: rename/delete, full-text search (highlight + keyboard + jump &
  flash), multi-select manage mode (batch delete/archive), Active/Archived
  views, JSONL export/import; message timestamps, 👍/👎 feedback, copy;
  slash-command grouped completions; mobile drawer sidebar, safe areas and
  touch targets.

### Earlier milestones (summary)
- M0–M3 capability seams: sessions (event JSONL + projections), tools, LLM
  streaming + providers, agent-loop, fs/subprocess/shell/sandbox,
  interactions (approval/ask-user), skills, subagents, settings/credentials,
  titles.
- Typed wire contract: `WebApiModels` DTOs + `SessionEventType` →
  `WebTypesGenerator` → `web-ui/src/types.ts`; backend serializes the same
  records.
- Entries: `majo` CLI one-shot, headless demo, `majo-web` app; external plugin
  jars via SPI (`--plugin`, `loadPluginJar`, hot `replaceJar`).

- **CI (roadmap E1) green on ubuntu** (`mvn clean verify` + vitest + plugin demo best-effort; jcordis-all/parent vendored under `lib/`); **frontend vitest suite (E2)** shipped (9 tests).

## [0.1.1] - 2026-09-08

Second tagged release (git `v0.1.0` → `v0.1.1`): the agent milestone closes
(M-C4 plugin islands + host-policy tooling), turns run in parallel per
session with per-stream approval routing, jcordis moves to Maven Central, and
the harness gets soak-grade verification plus network/health hardening. 系统
侧清单（#3/#6/#9/#10/#12/#13/#14/#16 等）全部落地；前端接线补齐（宿主命令
进 UI、离线工具卡广度）。

- **System items #3/#6/#13/#16**: host builtin backend commands (`status`,
  `delegate`) registered on `ctx.commands` and listed/run over REST; turns
  lock per session (parallel across sessions, real-browser verified with two
  tabs — approvals route to their own SSE stream, distinct `X-Turn-Id` per
  stream); every SSE stream emits a leading `turn` event carrying its id;
  `GET /api/openapi.json` serves the curated OpenAPI descriptor.

- **#12 host commands in the web UI**: `/status` shows the host counters;
  backend commands without a client twin are auto-registered into the
  composer completions (host group), so plugin-provided backend commands
  appear without a frontend rebuild.

- **A2 per-agent settings scoping end-to-end**: `delegate_task` and
  `POST /api/subagents/delegate` now accept an optional `settings` object
  forwarded into the scoped run (settings keys visible only inside the child
  scope, rolling back after). Test: an island tool reads `agent.tag` — alpha
  and beta children each see their own override while the root value stays
  untouched.

- **#10 web test-kit**: shared `TestProfiles` builds the offline mock profile
  for `ConcurrencySoakTest` and `OpenApiDriftTest` (gate/title/plugin/loop
  toggles) — the duplicated row blocks are gone.

- **#9 OpenAPI drift guard + two real fixes it caught**: new
  `OpenApiDriftTest` boots a live server and probes every path/method in the
  shipped `openapi.json` (unknown-path 404s and 500s fail). It surfaced:
  (1) empty chunked responses never closed the body stream, so the JDK
  HttpClient waited forever on the missing final chunk — export now closes
  the body after zero writes (empty sessions export fine via JDK clients);
  (2) `SettingsService` atomic writes got a short retry for transient
  Windows defender locks.

- **jcordis now from Maven Central**: jcordis is published under
  `io.github.yhnnhyyhnn` v1.0.1 (core + loader + parent + cli + maven-plugin),
  so majo-harness switched off the local 1.0.1-SNAPSHOT + vendored `lib/`:
  every module depends on `jcordis-core`/`jcordis-loader` (Central), CI drops
  its install-file step, and `lib/` is gone. No code changes — same
  `io.jcordis.*` imports.

- **D1 key-less search backend**: DuckDuckGo HTML search
  (`web-search-duckduckgo`, provider `duckduckgo`) — parsing is pure and
  offline-tested (titles/snippets/uddg-redirect URL normalization/limit);
  only the fetch needs the network. `web.yml` mounts it as the default
  search backend ahead of Wikipedia. Live HTTP check needs an internet
  connection (not available on this dev box).

- **Health/abort semantics + SSE heartbeat**: `/api/health` errors now count
  only genuine server failures — client aborts (IOException mid-response) are
  dropped silently; `streamTurn` writes go through one per-stream lock and a
  10s heartbeat comment frame keeps idle approval-pending streams alive
  (dead streams stop their heartbeat and never count as errors). Approval
  timeout default lowered 120s → 30s (still `majo.approvalTimeoutSeconds`).
  Soak suite grows to 7 tests (heartbeat visibility, abort-vs-error accounting).

- **Network hardening**: `majo-web` binds `127.0.0.1` by default; override
  with `--host <addr>` (or `majo.host`), and binding a non-loopback address
  without `--token` prints a warning. Verified by test.

- **Soak round 2**: 6 jar hot reloads race 8 concurrent cross-session turns
  (all answers stay correct; registry clean; unload works); approval timeouts
  are now configurable (`majo.approvalTimeoutSeconds`, default 30s), and a
  dropped approval-pending SSE stream fails safe without wedging later
  streams (verified end to end). `ConcurrencySoakTest` grows to 6 tests.

- **Parallel-turn soak + store fix**: new `ConcurrencySoakTest` hammers the
  real HTTP surface (24 concurrent cross-session turns, same-session bursts
  with concurrent deletes/health checks, correctness per session). The soak
  exposed a real bottleneck: `FileSessionStore` serialized every session on
  one monitor, so cross-session turns queued behind each other's file I/O —
  now locked per session file (parallel wall ≪ serialized control).

- **Demo depth (roadmap D2)**: offline `web_fetch`/`web_search` tool cards in
  web-mock via a new `local-file` fetch backend (`LocalFileFetchProvider`, root
  `examples/demo-corpus`, traversal-safe) + static search results; mock cue
  `fetch <url>` maps to `web_fetch`, answer prefix becomes `page: `. Verified
  in a real browser: fetch / search / shell cards all offline.

- **Agent M-C4**: host-policy plugin islands (`SubagentService.registerIsland` + `islands:` on `delegate_task`/REST) mount inside scoped runs and roll back with the scope. M-C1…C4 complete (no 1.1 wait; jcordis 1.0.1 now pulled from Maven Central).

- **CI green on the moved-to-Central build**: GitHub Actions passes with no
  vendored jcordis; the bubblewrap Linux test skips cleanly when the runner
  forbids unprivileged user namespaces (real confinement still verified where
  allowed).

## [0.2.0] - 2026-09-17

- **Runtime plugin mount + Maven builds**: `POST /api/plugins` mounts a jar
  (name+path) on a running server — it then hot-reloads and unloads through
  the existing endpoints (verified end-to-end against a live server). The
  plugin scaffold and the web-plugin-demo now ship a `pom.xml` (jcordis &
  harness as provided deps), so plugins build with plain Maven too. Also:
  route-level IO handling now only treats genuine socket/connection aborts
  as client aborts — handler IO failures (e.g. malformed JSON) surface as
  500s again instead of being silently swallowed.

- **Plugins panel hygiene by manifest `id`**: `plugin.json` gains an optional
  stable `id`; `GET /api/plugins` reports it (fallback: mount name) and the
  panel dedupes/flags duplicate mounts and unversioned jars by id instead of
  display title (pure helper + vitest). Examples + scaffold updated.

- **`/delegate` compose form**: the Subagents sidebar now has a first-class
  delegation form (task/model/maxSteps/auto-approve/allowedTools/host
  islands/per-agent settings key+value); backend exposes
  `GET /api/subagents/islands` (host-registered names). Browser-verified
  (mock child runs and appears in the activity list).

- **Live DDG probe**: `DdgLiveProbeTest` runs only under `MAJO_NET_PROBE=1`;
  CI adds a best-effort (continue-on-error) probe step on runners with
  outbound network.

- **Module-test script**: `scripts/test-module.sh <module>` runs tests inside
  the reactor (`-am`) so stale local-repo SNAPSHOTs can't fake "missing
  symbol" errors; README (EN/ZH) documents the pitfall.

- **Observability (/api/metrics)**: status histogram (1xx–5xx + client
  aborts), latency buckets (<5/20/100/500/2000 ms, over), and
  turns/approvalsDecided/questionsAnswered/pluginsReloaded counters;
  documented in openapi.json (drift-guarded).

- **P3 tool catalog, generative** (dsh docs/tool-catalog.md + verify):
  `ToolCatalogTest` boots the shipped offline profile and asserts
  `docs/tool-catalog.md` matches the live ToolRegistry (name/description/
  parameters per tool, 18 tools); `scripts/gen-tool-catalog.sh` regenerates
  (`-Dmajo.gen.tool-catalog=true`) — a tool change without regenerating
  fails the build.

- **P3 search indexing**: the debounced dashboard search no longer rescans
  and re-lowercases the whole log per keystroke — a versioned per-session
  entry cache (event-count version, ≤64 sessions) serves matches, invalidated
  on session delete; hit/snippet behavior is byte-identical.

- **P3 credentials by name — verified already shipped**: profiles reference
  `${ENV_VAR}` names (provider config expands them fail-loud; values never
  sit in config), `EnvCredentialProvider` resolves by name, and resolved
  values are redacted at durable boundaries. Roadmap item closed as
  confirmed-existing behavior.

- **P2-D trajectory + theme** (dsh `ui-trajectory` + `ui-theme`): a
  Chat/Trajectory view ring — the trajectory is a turn-grouped ledger of
  every durable event (request headers, approvals, compaction, bookkeeping —
  exactly the kinds the chat view hides) with per-turn durations, per-event
  deltas, and a text filter, over the existing session endpoint (no backend
  change). Theme switching cycles dark → light → system: the stylesheet was
  already token-based, so light is a variable re-aliasing block keyed on
  `data-theme`, tracking `prefers-color-scheme` live and persisted in
  localStorage. web-parity: theme/trajectory/context-meter rows now ✅.

- **P2-C compaction** (dsh `packages/compaction`): new `majo-compaction`
  module — the loop emits an `agent/before-request` waterfall before every
  model request, and the compaction listener summarizes over-budget history
  with the model itself and persists it as a durable `CONTEXT_COMPACTION`
  event; derivation restarts from the summary, so "model-visible means
  logged" survives the rewrite (asserted at ask time in CompactionTest).
  Token pressure uses a ~4-chars/token estimate; `GET /api/sessions/{id}/
  context` feeds a header Context Meter, and the host `/compact` command
  collapses history on demand without burning a conversation turn.

- **P2-B jobs** (dsh `packages/jobs`): new `majo-jobs` module — a per-session
  background-job registry (`shell-N` ids, concurrency cap, bounded output
  tails, live process handles) with `run_background` / `job_output` (optional
  wait) / `job_list` / `job_kill` tools. Completion rides the Phase-1 inbox:
  the plugin wires finished jobs to `followup`, so an idle harness wakes and
  a busy one delivers on its next turn. `GET /api/sessions/{id}/jobs` + a
  header jobs popover. In-process scope: jobs do not survive restarts.

- **P2-B schedule** (dsh `packages/schedule`): new `majo-schedule` module —
  `schedule_create` (`after_seconds` / `at` ISO / `every_seconds >= 300`),
  `schedule_list`, `schedule_delete`; records persist as `SCHEDULE_SET`
  events and the runtime re-arms timers from the session logs on mount
  (restart-safe, repeat schedules advance to the next unmissed occurrence;
  stale one-shots stay in the log unfired). Delivery is a follow-up turn to
  the owning session. `GET /api/sessions/{id}/schedules` + a header catalog.

- **P2-A todo** (dsh `packages/todo`): new `majo-todo` module — the
  `todo_write` tool replaces the session's whole task list durably
  (`TODO_SET` events), the `todo` projection folds the latest list,
  `GET /api/sessions/{id}/todos` serves it, and a conversation TodoPanel
  (☐/↻/✅) re-reads on session change and after each turn.

- **P2-A plan mode** (dsh `packages/plan/plan-mode`): new `majo-plan`
  module — the host `/plan` command arms plan mode per session
  (`PLAN_SET` events; the task text is model-visible via inbox inject),
  the model calls `exit_plan_mode` when its draft is ready and the human
  approves (deactivation recorded) or answers with feedback (plan stays
  active, feedback rides the tool result); `GET /api/sessions/{id}/plan`
  + a composer chip ("📋 plan mode · ✕") complete the loop. Reads
  degrade gracefully (empty/inactive) when the modules are not mounted.

- **P1 llm-replay** (dsh `test-support/llm-replay` analog): `RecordingChatModel`
  persists (request, streamed chunks, response) as `majo-llm-replay` v1 JSONL;
  `ReplayChatModel` replays in call order and fails loud on derived-request
  drift or script exhaustion — real-provider sessions become offline
  regression fixtures. `ChatResponse.isToolRound` is `@JsonIgnore` for clean
  fixtures.

- **P1 agent-loop dual inbox** (dsh next-turn/next-step): per-session
  `AgentInbox` — `followup` queues a next turn (idle wake via a
  virtual-thread driver whose polling stays under the per-session turn
  mutex; inline converge when a turn is running), `steer` splices durable
  user input at the next step boundary, `inject` lands a new
  `CONTEXT_NOTE` session event without ever waking. Turn-serialization is
  now owned by the loop itself. `AgentInboxTest` pins all four behaviors.

- **P1 approval audit + policy** (dsh user-approval): gated tool calls
  running inside a turn persist `APPROVAL_REQUESTED` + `APPROVAL_DECIDED`
  into the session log (policy resolutions audited with `source=policy`);
  session policy `session.approval.<id>` = `ask|never|auto` (fallback
  `defaultPolicy` config, fallback `ask`) decides before handlers, deny
  stays fail-closed. The loop binds each turn's session via
  `InteractionContext.runSession`; the audit pair stays out of derived
  model history. `ApprovalAuditTest`.

- **P1 "model-visible means logged" invariant**: `ModelVisibleMeansLoggedTest`
  rebuilds every model request from the durable log at ask time (system
  prompt + `MessageDeriver.derive`) and fails on any divergence — covering
  steered input, injected notes, and tool rounds. `scripts/check.sh` is the
  single local/CI gate entry (no-stdout, ESLint, vitest, Maven verify).

- **Docs parity**: architecture & web-parity (EN/ZH) now reflect the current
  endpoints (commands/health/metrics/openapi/plugin reload/…), host command
  UI bridge, subagent islands + settings overrides, plugins panel, offline
  fetch/search backends and profiles.

- **Engineering foundation (roadmap-0.3 Phase 0, dsh-informed)**: the
  1715-line `WebMain` split into `WebMain` (assembly) + `Router` (pattern
  route table) + a per-domain `handler/` package + extracted
  `Metrics`/`PendingInteractions`/`Http`/`WebContext`; behavior preserved
  (OpenApiDriftTest + ConcurrencySoakTest green, openapi.json untouched).
  Token comparison is now constant-time (`MessageDigest.isEqual`), the
  version in `/api/info` + `/api/health` comes from a Maven-filtered
  resource, service logging moved to slf4j (+`slf4j-simple`) with a
  `scripts/verify-no-stdout.sh` gate, and the README (EN/ZH) gained a
  security model section (`?token=` SSE caveat included). Frontend: the
  783-line `App.tsx` shrank to the shell with new
  `components/SessionSidebar|PluginFrame|Composer`, a shared
  `usePollingSection` hook replaces three copy-pasted poll loops, and ESLint
  9 (flat config, `npm run lint`) runs clean. Docs: `docs/roadmap-0.3(.zh-CN).md`
  plans Phases 1–3 (agent-loop inbox, durable approval audit, record/replay
  LLM tests, plan/todo/jobs/schedule/compaction/trajectory parity); the
  web-parity markdown-table row is corrected to ✅ (GFM tables + language
  captions shipped with tests).

## [0.3.0] - 2026-09-18

Working area for the next iteration.

- **MCP client** (roadmap-0.4 Phase 2, the dsh MCP analog): new `majo-mcp`
  module — profile rows declare stdio servers (`command`/`args`/`env`, env
  values reference variables by name, credentials never in the profile);
  the plugin spawns each server, runs the JSON-RPC 2.0 initialize handshake,
  and bridges `tools/list` into the `ToolRegistry` as namespaced
  `mcp__<server>__<tool>` tools with verbatim JSON schemas — they appear in
  `/api/tools` and the generated tool catalog for free, and every call rides
  the ordinary approval seam. Calls are bounded by `requestTimeoutSeconds`
  (default 10); MCP errors surface as ordinary tool errors; a failed mount
  is logged loudly but never breaks boot or the other servers; unmount
  closes the server processes. Verified end-to-end in CI against an in-repo
  echo MCP server (real subprocess). MCP 客户端：stdio 服务器按 profile 行
  声明，工具以命名空间桥入 ToolRegistry，审批接缝/超时/fail-loud 兼备。

- **Tool-result pruning** (roadmap-0.4 Phase 1, dsh `compaction` analog):
  in derived history, tool results older than the final assistant round
  collapse to `[pruned tool result: N chars]` placeholders once they exceed
  the configurable threshold (`pruneChars`, default 4000); the newest round
  stays intact and the durable log is untouched — pruning is a deterministic
  function of the log, so the pipeline invariant (request == system +
  prune(derive(log))) holds at ask time. 上下文压力管理补齐：超长工具结果
  在派生历史中折叠为占位符，最新一轮保持原样。

- **LLM fault injection** (roadmap-0.4 Phase 1, dsh
  `llm-mock-server` analog): `FaultLlmServer` — a raw-socket scripted HTTP
  server that controls the exact wire bytes (status/headers/body, and
  Content-Length lies for real mid-body disconnects) — plus
  `FaultChatModel`, the seam-level wrapper whose script throws on the n-th
  call or kills a stream after n deltas. Wire tests (`OpenAiFaultTest`) pin
  the provider contract: mid-stream disconnects, 429/5xx, malformed SSE
  chunks, in-stream error objects, and non-JSON bodies all fail loudly as
  `ModelException` — never a silently truncated "success", and no hidden
  adapter retry (429 → exactly one request). Loop tests (`FaultTurnTest`)
  pin the turn contract: a model failure fails the turn loudly, the session
  log keeps only the open turn (no half-committed assistant round, no
  TURN_END), the session recovers on the next turn, and a failed driver
  turn cannot wedge the inbox queue. LLM 故障注入：线级脚本 server + 接缝级
  包装器；断流/429/5xx/畸形 chunk 全部 loud 失败，回合无半提交。

- **Session file generations** (roadmap-0.4 Phase 1, dsh
  `session-persistence-jsonl` analog): durable session logs are now named
  `<id>.v1.jsonl` and start with a one-line format header; `SessionFileFormat`
  adds header-only `stat`/`listStats` (directory listings never parse event
  bodies), one-step legacy migration (`<id>.jsonl` migrates transparently on
  first touch), and loud downgrade protection for newer generations. A
  partial trailing line from a crash is now repaired on read (it was never a
  committed event); a complete corrupt line still fails loudly. New cheap
  `eventCount` seam — `GET /api/sessions` stops full-parsing every log per
  poll. 会话文件代际化：`<id>.v1.jsonl` + 格式头 + 一步迁移 + 降级保护；
  会话列表不再全量解析。

## [0.4.0] - 2026-09-18

- **MCP remote live probe**: `McpRemoteLiveProbeTest` exercises the HTTP
  transport against a real hosted server (`mcp.deepwiki.com/mcp`, no auth)
  — handshake, tool list, and a `read_wiki_contents` round-trip. Same
  `MAJO_MCP_PROBE=1` gate and best-effort CI step as the filesystem probe.
  远程实测：对公网托管 server 验证 Streamable HTTP 传输全链路。

- **MCP Streamable HTTP transport** (roadmap-0.5 Phase 1): server rows
  accept a `url` + `headers` form alongside `command` stdio — remote MCP
  servers over Streamable HTTP, answering either single JSON bodies or
  SSE streams; the `Mcp-Session-Id` from initialize is captured and echoed,
  and `DELETE` ends the session on unmount. Header values reference
  environment variables by name (bearer/custom headers only — no OAuth, a
  documented limitation). A row must carry exactly one of `command`/`url`.
  The transport seam split into `McpStdioConnection`/`McpHttpConnection`
  behind the `McpConnection` interface; tested in-process for both wire
  shapes. MCP 远程传输：url + headers 形式挂载远程 server，JSON/SSE 双响应，
  会话头贯穿。
- **MCP prompts & resources**: servers declaring the capability get one
  namespaced read-only tool each — `mcp__<server>__read_resource`
  (resources enumerated in the description, `resources/read` behind it) and
  `mcp__<server>__get_prompt` (`prompts/get` rendering `role: text`
  lines). Prompts land as a tool rather than the commands seam because the
  command registry lives in `majo-boot`, which already depends on
  `majo-mcp` — the reverse edge would be circular.
- **Hot-path hardening**: session-append sequence numbers are memoized per
  session (one derivation parse per session per process; remove forgets the
  memo, imports extend it) — appends no longer re-parse the whole log;
  derived session titles memoize once (the sidebar poll previously
  re-parsed every untitled session's full log each cycle) and a
  re-registered title provider forgets the memo. 热路径加固：seq 推导与
  标题派生各只解析一次，侧栏轮询零全量解析。

- **Composer font-size control** (dsh ui-theme stretch): header toggle
  cycles small → medium → large, persisted in localStorage, applied via a
  `data-composer-size` attribute the stylesheet keys on.

- **MCP live probe**: `McpFilesystemLiveProbeTest` runs the client against
  the real ecosystem's `@modelcontextprotocol/server-filesystem` (handshake,
  full tool list, `read_file` round-trip through the registry bridge,
  unmount teardown). Gated by `MAJO_MCP_PROBE=1` and run best-effort in CI,
  like the DuckDuckGo probe; verification docs gained the manual command.
  MCP 实测探针：对官方 filesystem server 验证握手/工具清单/读取往返。

## [Unreleased]

Working area for the next iteration (roadmap-0.5).

- **Request-context injection** (new `majo-context` module, dsh `context`
  family / roadmap-0.5 candidate adopted): once per session, at the first
  turn start, the `context` plugin durably appends a `CONTEXT_NOTE`
  carrying workspace instructions (AGENTS.md / CLAUDE.md, read at mount)
  and the current time. The note is model-visible user-role content — it
  replays, survives resume, and compacts like any other context.
  Dedupe is two-layer (in-process set + log-marker scan), so restarted
  hosts never re-inject. Config: `{dir, files, timeContext, maxChars}`;
  mounted by default in the web profiles.
  上下文注入：工作区指令 + 时间上下文每会话一次性持久注入。
- **LLM-backed session titles** (`session-title-llm` plugin row, dsh
  parity): the model proposes a short title from the session's first user
  message — mount it *instead of* `session-title-heuristic` (sole-provider
  semantics fail loudly when both mount). Successful derivations memoize
  (one call per session); failures and empty answers back off per prompt so
  sidebar polling never hammers the model. LLM 标题：模型从首条用户消息
  起题，成功一次备忘，失败按输入退避。

- **Workflow v1 design proposal** (`docs/workflow-design(.zh-CN).md`):
  named YAML step lists (turn / delegate steps, `{{args}}`/`{{steps}}`
  templates) running in a dedicated child session, `WORKFLOW_*` durable
  events, `/workflow` command + `workflow_run` tool. Design only —
  implementation follows after review.

- **dsh reference audit 2026-09-18** (`docs/audit-dsh-2026-09(.zh-CN).md`):
  incremental audit after the reference moved ~1,700 commits. Core
  mechanisms (agent-loop/inbox, approval audit, jobs/schedule, llm-replay)
  unchanged — majo's ports stay aligned. Three adjustments adopted:
  (1) **tool-call timeout** (`tools` plugin `toolTimeoutSeconds`; web
  profiles enable 600s — hung calls return a clear timed-out error, dsh
  guard timeout-policy analog); (2) **tool-result pruning keeps
  head+tail** (defaults 4096/1024 behind a marker line, dsh
  compaction-tool-result-pruner shape; `pruneChars` default aligned
  4000 → 8192); (3) **MCP stdio env scrubbing** (spawned servers see only
  an allowlisted subset of the ambient env plus explicit `env` — ambient
  credentials cannot leak, dsh scrubbedParentEnv analog). Deliberate
  divergences documented (prompts bridged, per-server resource tools,
  `auto` policy value, session format v1); future candidates listed
  (context injection, spill, ssh, ptc-runtime, MCP reconnect).
  参考项目增量审计：核心机制无变化；采纳三处调整；分歧与候选成文。
