# Verification checklist (0.1.0)

Manual/scripted pass used before tagging a release. Run from the repo root on
Windows (Git Bash) or POSIX.

## 1. Backend build & tests

```bash
mvn clean verify            # compiles all modules + runs every seam test + rebuilds the UI into the jar
```

Expected: `BUILD SUCCESS`. 40+ test classes cover stores, seams (fs/shell/
subprocess/sandbox/interaction/skill/subagent/title/web-access), agent loop
(parallel delegation, scoped runs, max-steps caps), settings and the wire
generator.

Concurrency soak (`majo-web` `ConcurrencySoakTest`, HTTP-level): 24
concurrent cross-session turns each answer their own expression and complete
well under the serialized control time (per-session locks + per-session-file
store locking); 10 same-session turns burst safely while another session is
repeatedly deleted and `/api/health` is polled; 8 independent HTTP clients
overlap without errors. More: 6 plugin-jar hot reloads during 8 concurrent
sessions keep every turn correct and end with a clean registry/unload; an
approval-pending SSE stream dropped mid-flight times out (configurable via
`majo.approvalTimeoutSeconds`) and never wedges a later stream (which still
gets its own approval and completes).

## 2. Plugin demo jar + web serving

```bash
bash scripts/build-plugin-demo.sh                 # builds examples/web-plugin-demo/web-demo.jar
java -jar majo-web/target/majo-web-0.1.0-SNAPSHOT.jar \
  --port 8899 --profile web-mock \
  --plugin web-demo=./examples/web-plugin-demo/web-demo.jar
```

Check (curl or browser):

- `GET /api/plugins` lists web-demo with `module` URL.
- `GET /plugins/web-demo/index.html` and `/plugin.mjs` return 200 with
  `text/javascript` for the mjs.
- Browser (any): Plugins section shows web-demo; Native demo sidebar section
  appears; **unload** removes it, **mount/reload** restore it without a page
  refresh.
- Traversal (`/plugins/web-demo/../…`) and unknown plugins return 404.

## 3. Web UI functional pass (browser)

Serve `--profile web-mock --port 8899`, open http://localhost:8899:

- Binding: default `127.0.0.1`; use `--host 0.0.0.0` only with `--token`
  (non-loopback binds warn when tokenless).

- Chat: type `1+2` → approval rail → Allow → `calculated: 3`; streams live.
- Optional API auth: start with `--token <secret>`; the UI reads
  `?token=…` (persisted), curl uses `Authorization: Bearer <secret>`, SSE
  uses `?token=`.
- Tool cards offline (web-mock): `file examples/offline-demo/hello.txt` → Allow →
  file card with path chip and line count; `shell echo hi-d2` → Allow → shell
  output card (`shell output: hi-d2`); `fetch file:hello-page.txt` → Allow →
  fetch card with the local-file corpus page (zero network); `search majo` →
  Allow → static result cards (`Majo FAQ`, …). All fully offline: the local
  fetch backend only ever reads `examples/demo-corpus` and rejects traversal.
- Model pickers (global + per-session), 👍/👎 feedback persists after reload.
- Slash commands: type `/` → grouped completions; `/model mock`, `/help`,
  `/delegate 2+2` (returns a child id + answer).
- Session search: query `calculated` → highlighted hit → Enter/click jumps and
  flashes the message.
- Manage mode: multi-select → Archive (n) / Delete (n); Active/Archived views;
  archived hits in search have an inline restore.
- Export ⬇ → import via **Import JSONL…** round-trips a transcript.
- Parallel turns (verified in a real browser with two tabs): session A
  `3+3` and session B `4+4` both sit in the approval rail at once (per-session
  locks, no global serialization); each tab carries its own `X-Turn-Id`;
  Allow on A yields only `calculated: 6` on A and B stays pending/unanswered;
  Allow on B yields only `calculated: 8`. Decisions never cross streams.
- Plugins + mobile (DevTools narrow viewport): drawer sidebar, touch targets,
  no iOS zoom.
- Dev full-stack alternative:
  `java -jar … --port 8899 --profile web-mock` +
  `MAJO_API_TARGET=http://127.0.0.1:8899 npx vite --port 5173`.

## 4. Real-model (optional, network)

```bash
java -jar majo-web/target/majo-web-0.1.0-SNAPSHOT.jar --profile web --port 8900
curl -X POST -H 'Content-Type: application/json' \
  -d '{"task":"What is 33 times 4?","model":"kilo-free"}' \
  http://127.0.0.1:8900/api/subagents/delegate
# expect {"childSessionId":…,"answer":"132"}; panel shows model kilo-free
```

Fan-out SSE check: a prompt asking for two `delegate_task` calls in one round
produces per-child approvals tagged `subagent-<child8>`; allowing each yields a
correct combined answer.

## 5. Git hygiene

- `git status` clean; `git push origin main` up to date.
- Annotated release tag on HEAD (`v0.1.0`).
