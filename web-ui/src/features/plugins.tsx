import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import type { PluginInfo } from "../types";
import type { Feature, PluginHost, SectionProps } from "../slots";
import { useRegistrar } from "../slots";
import { pluginIssues } from "../plugin-issues";

// Mounted plugin frontends: the backend hosts each plugin jar's
// static-web/<name>/ assets under /plugins/<name>/; this section lists what
// is mounted and opens the chosen plugin in the main pane.
//
// Native plugins: when an entry carries a `module` URL, the loader fetches it
// at runtime (dynamic import, @vite-ignore so the host never bundles it) and
// calls its exported `register(host)`. host carries the shared React instance,
// the api client, seats (openPlugin/flash) and a Registrar whose every add
// returns a rollback disposer — the same slot contract compiled-in features
// use, now at runtime. Loaded modules can be unloaded (disposer runs and the
// slot contribution rolls back) or reloaded (cache-busted re-import).

const loadedModules = new Set<string>();

function PluginsMenu({ openPlugin }: { openPlugin?: (name: string, url: string) => void }) {
  const registrar = useRegistrar();
  const registrarRef = useRef(registrar);
  registrarRef.current = registrar;
  const [open, setOpen] = useState(false);
  const [plugins, setPlugins] = useState<PluginInfo[] | null>(null);
  const [nativeStates, setNativeStates] = useState<Record<string, string>>({});
  const disposersRef = useRef(new Map<string, () => void>());
  const mtimesRef = useRef(new Map<string, number>());
  const lastNotifiedRef = useRef(new Set<string>());

  const flash = (message: string) => {
    window.dispatchEvent(new CustomEvent("majo:flash", { detail: message }));
  };

  const loadModule = async (plugin: PluginInfo, url: string) => {
    if (!url) return;
    setNativeStates((s) => ({ ...s, [plugin.name]: "loading" }));
    try {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const mod = (await import(/* @vite-ignore */ url)) as {
        register?: (host: PluginHost) => unknown;
      };
      if (typeof mod.register !== "function") {
        throw new Error("module has no register(host) export");
      }
      const host: PluginHost = {
        React: await import("react"),
        api,
        openPlugin: (name, page) => openPlugin?.(name, page),
        flash,
        registrar: registrarRef.current,
      };
      const disposer = mod.register(host);
      if (typeof disposer === "function") {
        disposersRef.current.set(plugin.name, disposer as () => void);
      }
      loadedModules.add(plugin.name);
      setNativeStates((s) => ({ ...s, [plugin.name]: "mounted" }));
    } catch (error) {
      loadedModules.delete(plugin.name);
      setNativeStates((s) => ({ ...s, [plugin.name]: "error" }));
      console.error("plugin module load failed", plugin.name, error);
      flash("plugin module failed to load: " + plugin.name);
    }
  };

  const mountNative = (plugin: PluginInfo) => {
    if (!plugin.module) return;
    void loadModule(plugin, plugin.module);
  };

  const unloadNative = (plugin: PluginInfo) => {
    const disposer = disposersRef.current.get(plugin.name);
    try {
      disposer?.();
    } catch (error) {
      console.error("plugin dispose failed", plugin.name, error);
    }
    disposersRef.current.delete(plugin.name);
    loadedModules.delete(plugin.name);
    setNativeStates((s) => ({ ...s, [plugin.name]: "unloaded" }));
    flash("unloaded " + plugin.name);
  };

  const reloadNative = (plugin: PluginInfo) => {
    unloadNative(plugin);
    if (!plugin.module) return;
    const bust =
      plugin.module + (plugin.module.includes("?") ? "&" : "?") + "v=" + Date.now();
    void loadModule(plugin, bust);
  };

  const load = async () => {
    const index = await api.plugins();
    setPlugins(index.plugins || []);
    // C3: jar watch via polling — if a native module's jar mtime changed,
    // reload it automatically (cache-bust re-import) and flash once.
    for (const plugin of index.plugins || []) {
      if (!plugin.module || typeof plugin.mtime !== "number") continue;
      const known = mtimesRef.current.get(plugin.name);
      mtimesRef.current.set(plugin.name, plugin.mtime);
      const mounted = loadedModules.has(plugin.name);
      if (mounted && known !== undefined && known !== plugin.mtime) {
        reloadNative(plugin);
        if (!lastNotifiedRef.current.has(plugin.name + plugin.mtime)) {
          lastNotifiedRef.current.add(plugin.name + plugin.mtime);
          flash("plugin " + plugin.name + " updated — reloaded");
        }
      }
    }
    return index.plugins || [];
  };

  const autoMount = (plugin: PluginInfo) => {
    if (plugin.module && !loadedModules.has(plugin.name)) mountNative(plugin);
  };

  useEffect(() => {
    if (!open) return;
    void load()
      .then((list) => {
        for (const plugin of list) autoMount(plugin);
      })
      .catch(() => setPlugins([]));
    const timer = window.setInterval(() => {
      void load().then((list) => {
        for (const plugin of list) autoMount(plugin);
      });
    }, 5000);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    return () => {
      for (const disposer of disposersRef.current.values()) {
        try {
          disposer();
        } catch {
          // ignore unload errors during teardown
        }
      }
      disposersRef.current.clear();
      loadedModules.clear();
    };
  }, []);

  return (
    <div className="side-section">
      <button type="button" className="side-section-head" onClick={() => setOpen(!open)}>
        Plugins {plugins ? `(${plugins.length})` : ""}{" "}
        <span className="caret">{open ? "▾" : "▸"}</span>
      </button>
      {open && (
        <div className="side-section-body">
          {plugins === null && <div className="meta">loading…</div>}
          {plugins && plugins.length === 0 && (
            <div className="meta">
              none mounted — start with <code>--plugin name=jar</code>
            </div>
          )}
          {plugins && plugins.length > 0 && (
            <div className="meta plugin-warnings">
              {pluginIssues(plugins).map((issue) => (
                <div key={issue}>⚠ {issue}</div>
              ))}
            </div>
          )}
          {plugins?.map((plugin) => {
            const state = nativeStates[plugin.name] ?? "pending";
            const mounted = loadedModules.has(plugin.name);
            return (
              <div key={plugin.name} className="side-item plugin-entry">
                <button
                  type="button"
                  className="side-refresh plugin-open"
                  title={plugin.module ? "native module (component in slots)" : "hosted page"}
                  onClick={() => {
                    if (!plugin.module) {
                      openPlugin?.(plugin.name, plugin.url);
                      return;
                    }
                    if (!mounted) mountNative(plugin);
                    else if (state === "mounted")
                      flash(plugin.title || plugin.name + " is mounted — its sidebar section is live");
                  }}
                >
                  ▶ {plugin.title || plugin.name}
                  {plugin.module && (
                    <span className={"meta native-dot " + state}>
                      {state === "error"
                        ? " · load failed"
                        : state === "mounted"
                          ? " · native ✓"
                          : state === "unloaded"
                            ? " · unloaded"
                            : " · native"}
                    </span>
                  )}
                </button>
                {(plugin.version || plugin.slots?.length) && (
                  <div className="meta plugin-meta">
                    {plugin.version && <span>v{plugin.version}</span>}
                    {plugin.slots?.length ? <span>slots [{plugin.slots.join(", ")}]</span> : null}
                  </div>
                )}
                {plugin.module && (
                  <div className="plugin-actions">
                    <button type="button" className="side-refresh" onClick={() => openPlugin?.(plugin.name, plugin.url)}>
                      page
                    </button>
                    {mounted ? (
                      <>
                        <button type="button" className="side-refresh" onClick={() => reloadNative(plugin)}>
                          reload
                        </button>
                        <button type="button" className="side-refresh" onClick={() => unloadNative(plugin)}>
                          unload
                        </button>
                      </>
                    ) : (
                      <button type="button" className="side-refresh" onClick={() => mountNative(plugin)}>
                        mount
                      </button>
                    )}
                  </div>
                )}
              </div>
            );
          })}
          {plugins && plugins.length > 0 && (
            <button type="button" className="side-refresh" onClick={() => void load()}>
              refresh list
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function section(props: SectionProps) {
  return <PluginsMenu openPlugin={props.openPlugin} />;
}

export const pluginsFeature: Feature = {
  id: "plugins",
  register(context) {
    context.addSidebarSection("plugins", section);
  },
};
