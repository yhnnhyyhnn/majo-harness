# Plugin development guide

A plugin is a **self-contained jar** that plugs into the harness at runtime —
no web-ui rebuild, no host code change. Depending on how much UI it needs, a
plugin picks one of three integration tiers (they compose freely in one jar):

| Tier | What it ships | Integration | Rebuild web-ui? |
|---|---|---|---|
| 1 · Backend | jcordis `Plugin` (SPI) | ctx services / tools / providers / listeners | no |
| 2 · Hosted page | static frontend under `static-web/<name>/` | independent page served at `/plugins/<name>/`, same-origin API + postMessage bridge | no |
| 3 · Native module | `plugin.mjs` (ES module) | components registered into host slots at runtime | no |

The shipped example (`examples/web-plugin-demo`, buildable via
`bash scripts/build-plugin-demo.sh`) demonstrates all three tiers in one jar.
The `majo-boot` `PluginJarTest` is a ready recipe for building tier-1 jars
with Maven.

---

## Mounting

```bash
# web app: jar plugins (repeatable) + profile
java -jar majo-web/target/majo-web-0.1.0-SNAPSHOT.jar \
  --profile web-mock \
  --plugin web-demo=./examples/web-plugin-demo/web-demo.jar

# CLI one-shot
majo --plugin ext=./ext-plugin.jar "task"
```

Profile rows may then reference the plugin **by its mount name**, e.g.

```yaml
- id: my-capability
  name: my-plugin            # the --plugin name
  config:
    key: value
```

---

## Jar layout (one jar, up to three tiers)

```
my-plugin.jar
├── io/…/MyPlugin.class …              # tier 1 backend classes
├── META-INF/services/
│   └── io.jcordis.core.registry.Plugin   # SPI line: fqcn of your Plugin
└── static-web/my-plugin/               # tiers 2+3 frontends
    ├── index.html                      # hosted page entry (tier 2)
    ├── app.js / assets/…               # page assets (same origin)
    ├── plugin.json                     # optional: { "title": "…" }
    └── plugin.mjs                      # native module (tier 3)
```

`static-web/<name>/` must match the **mount name** (`--plugin name=jar`).
The host serves every file under it at `GET /plugins/<name>/…` (path
traversal guarded; `html/css/js/svg/png/jpg/ico/woff2/json` content types
shipped) and lists plugins with an `index.html` via `GET /api/plugins`.

---

## Tier 1 — backend plugin

Implement `io.jcordis.core.registry.Plugin` (instance contract, not a
constructor):

```java
public final class MyPlugin implements Plugin {
    @Override public String name() { return "my-plugin"; }
    @Override public Map<String, Object> inject() { return Map.of("llm", null); }
    @Override public Object apply(Context ctx, Object config) {
        Disposable registration = ctx.get("tools") /* register your tool/… */;
        return registration;   // returned Disposable is collected & rolled back on unload
    }
}
```

- `apply` runs once the declared injections resolve; throw loudly on bad
  config; **return a `Disposable`** (jcordis collects it and rolls back on
  unload).
- Your SPI file line: `META-INF/services/io.jcordis.core.registry.Plugin`
  containing your fully qualified class name.
- Whatever you register through ctx is then available to the agent (tools are
  the natural bridge to the UI: their structured `data` renders as cards).

---

## Tier 2 — hosted page plugin

Ship a static web app (any tech) under `static-web/<name>/`. The host serves
it; the main UI shows it under the sidebar **Plugins** section and opens it in
a sandboxed iframe in the main pane.

- Same-origin: call `fetch("/api/sessions")`, `…/api/info`, SSE etc. directly.
- To drive the host (close the pane, open a session, start a turn, show a
  notice) post a message — the host validates `event.source` and
  `source === "majo-plugin"`:

```js
window.parent?.postMessage(
  { source: "majo-plugin", type: "sendTask", task: "2+2" },
  "*"
);
```

| `type` | payload | host behaviour |
|---|---|---|
| `flash` | `message: string` | transient notice banner |
| `newChat` | — | start a new conversation |
| `close` | — | back to the chat view |
| `sendTask` | `task: string` | close the pane and run the task as a turn |
| `openSession` | `sessionId: string` | close the pane and open that session |

Optional `plugin.json` supplies the menu title:

```json
{ "title": "my-plugin" }
```

---

## Tier 3 — native module (`plugin.mjs`)

The strongest integration: the host **dynamically imports** the module at
runtime (never bundled into web-ui) and hands it a host object. The module
may register components/sections/commands into the very same slots compiled-in
features use.

Contract:

```js
// plugin.mjs — must export register(host); may return a disposer
export function register(host) {
  const { React } = host;            // THE shared React instance
  const dispose = host.registrar.addSidebarSection("my-section", () =>
    React.createElement(MySection)   // …or compiled JSX
  );
  return dispose;                    // rollback when unloaded
}
```

`host` (PluginHost):

| field | meaning |
|---|---|
| `React` | the host's React (createElement/hooks) — **never bundle your own** |
| `api` | typed API client (same endpoints as the chat UI) |
| `openPlugin(name, url)` / `flash(message)` | seats to open a hosted page / show a notice |
| `registrar` | `addSidebarSection` / `addCommand` / `addRail` / `renderMessage`, each **returns a rollback disposer** |

Authoring with JSX is fine if you compile before shipping — keep `react`
external and render through `host.React`:

```bash
# esbuild example (plugin source in src/, output static-web/my-plugin/plugin.mjs)
esbuild src/index.jsx --bundle --format=esm --external:react \
  --outfile=src/main/resources/static-web/my-plugin/plugin.mjs
```

Notes: hooks work because the module uses the host's single React instance.
Loaded modules live for the page session; a reload re-runs `register`.

---

## Verifying a plugin

1. `bash scripts/build-plugin-demo.sh` → `examples/web-plugin-demo/web-demo.jar`.
2. Start `majo-web` with `--plugin web-demo=…` (see Mounting).
3. `curl http://localhost:8787/api/plugins` lists it (with `title`/`module`).
4. `curl http://localhost:8787/plugins/web-demo/index.html` serves the page.
5. Open the UI: sidebar **Plugins** section shows it; opening the page shows
   tier 2; the **Native demo** sidebar section appears from tier 3 (browser
   check).

Troubleshooting: `--plugin` expects `name=jar`; the `static-web/<name>/`
folder must equal the mount name; boot logs `mounted plugin "name" from …`;
unknown plugin / traversal paths return 404.
