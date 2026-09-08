# Roadmap 0.2 (candidate initiatives)

Working list for the next release cycle. Items are grouped by theme with a
rough effort/impact rating; pick per sprint. Status lives in CHANGELOG
(`## [Unreleased]`).

> **Status (2026-09, v0.1.1):** everything below is shipped or superseded —
> A1 became the jcordis agent-fiber semantics, A2 landed as per-agent
> settings scoping, B1/C1–C3/D2/E1/E2 shipped, and the original D1 split
> into the DuckDuckGo backend here plus a live-network validation that needs
> an internet-enabled host. The only un-done item is **E3 real-device mobile
> QA** (manual, requires a physical phone).

## A. Agent context depth (follow-on to M-C1…C3)

- **A1 · jcordis sub-context / independent-fiber experiment** (effort M,
  enabler). Harness-side scoping currently relies on plugin-fiber disposal on
  `Context.extend()` children; to reach full "plugin islands per agent"
  (M-C4) we first need jcordis primitives for a per-child fiber with clean
  teardown and config isolation. Deliverable: a small SPI experiment +
  proposal, not production code yet.
- **A2 · per-agent settings/credential/title scoping** (effort S–M): agents
  inherit root defaults but may override settings keys (e.g. a `web.model`
  equivalent) within their scope; verify isolation via the existing
  InteractionContext-style thread-locals or scope intercepts.
- **A3 · web SSE approval UX** (effort S): collapsed "n delegations awaiting"
  summary on the rail with expand-per-agent, rather than one card per child.

## B. Commands & control surface

- **B1 · backend command registry** (effort M): slash commands currently live
  client-side; a server-side `ctx.commands` seam would let plugins contribute
  commands that run with harness context (and mirror the client ones).
- **B2 · `/delegate` UI affordances** (effort S): picker for model/spec fields
  in the composer or a small form; open the child transcript after a REST
  delegation.

## C. Plugin ecosystem

- **C1 · plugin template repo/scaffold** (effort S–M): cookiecutter-style
  starter (backend SPI + `static-web` page + `plugin.mjs` native module),
  publishable as a GitHub template.
- **C2 · versioned plugin manifests** (effort S): `plugin.json` gains
  `version`/`slots`; `GET /api/plugins` reports them and the UI can warn on
  duplicate ids or stale reloads.
- **C3 · plugin jar watcher** (effort M): watch a directory; on jar change,
  notify the browser to reload the native module (cache-bust already works).

## D. Providers & offline demo

- **D1 · more real SearchProvider backends** (effort M): e.g. DuckDuckGo
  Lite HTML or SerpAPI-style without keys where possible; needs network for
  live checks (offline parse tests still cover mapping).
- **D2 · fetch-to-file / shell demo turns in web-mock** (effort S): mount fs
  tool cards through the cue mock so tool cards are demonstrable fully
  offline.

## E. Engineering hygiene

- **E1 · CI ✅** (effort S): GitHub Actions running `mvn clean verify` + plugin demo + vitest — green on ubuntu (jcordis-all/parent vendored in lib/) `mvn clean verify` +
  `scripts/build-plugin-demo.sh`; optional headless browser smoke once a
  browser runtime is available in CI.
- **E2 · frontend unit tests ✅** (effort M): vitest covers chat/search-block, markdown-table and command-completion helpers (9 tests),
  search/keyboard helpers, command completion grouping — no browser needed.
- **E3 · real-device mobile pass** (effort S): one QA round on an actual phone
  (safe areas, touch targets, drawer) and fix what falls out.

## Suggested sequencing

1. E1/E2 (hygiene, unblocks future work).
2. A1 (enabler) with B1 alongside (independent).
3. C1/C2 (ecosystem), then D1/D2 demo breadth.
4. A2/A3 and C3 as UX depth allows.

Acceptance for 0.2: everything above merged keeps `mvn clean verify` green and
the verification checklist (docs/verification.md) current.
