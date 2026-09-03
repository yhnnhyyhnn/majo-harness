import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import type { PluginInfo } from "../types";
import type { Feature, PluginHost, SectionProps } from "../slots";
import { useRegistrar } from "../slots";

// Mounted plugin frontends: the backend hosts each plugin jar's
// static-web/<name>/ assets under /plugins/<name>/; this section lists what
// is mounted and opens the chosen plugin in the main pane.
//
// Native plugins: when an entry carries a `module` URL, the loader fetches it
// at runtime (dynamic import, @vite-ignore so the host never bundles it) and
// calls its exported `register(host)`. host carries the shared React instance,
// the api client, seats (openPlugin/flash) and a Registrar whose every add
// returns a rollback disposer — the same slot contract compiled-in features
// use, now at runtime (S3-lite). Loaded modules persist for the page session.

const loadedModules = new Set<string>();

function PluginsMenu({ openPlugin }: { openPlugin?: (name: string, url: string) => void }) {
  const registrar = useRegistrar();
  const registrarRef = useRef(registrar);
  registrarRef.current = registrar;
  const [open, setOpen] = useState(false);
  const [plugins, setPlugins] = useState<PluginInfo[] | null>(null);
  const [nativeStates, setNativeStates] = useState<Record<string, string>>({});
  const disposersRef = useRef<(() => void)[]>([]);

  const load = async () => {
    const index = await api.plugins();
    setPlugins(index.plugins || []);
    return index.plugins || [];
  };

  const mountNative = async (plugin: PluginInfo, hostFlash: (m: string) => void) => {
    if (!plugin.module || loadedModules.has(plugin.name)) return;
    loadedModules.add(plugin.name);
    setNativeStates((s) => ({ ...s, [plugin.name]: "loading" }));
    try {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const mod = (await import(/* @vite-ignore */ plugin.module)) as {
        register?: (host: PluginHost) => unknown;
      };
      if (typeof mod.register !== "function") {
        throw new Error("module has no register(host) export");
      }
      const host: PluginHost = {
        React: await import("react"),
        api,
        openPlugin: (name, url) => openPlugin?.(name, url),
        flash: hostFlash,
        registrar: registrarRef.current,
      };
      const disposer = mod.register(host);
      if (typeof disposer === "function") {
        disposersRef.current.push(disposer as () => void);
      }
      setNativeStates((s) => ({ ...s, [plugin.name]: "mounted" }));
    } catch (error) {
      loadedModules.delete(plugin.name);
      setNativeStates((s) => ({ ...s, [plugin.name]: "error" }));
      console.error("plugin module load failed", plugin.name, error);
    }
  };

  const flash = (message: string) => {
    // notices live in the chat controller; plugins menu has no actions seat,
    // so route through the registrar-free path: dispatch a window event the
    // shell listens for (see AppShell plugin bridge).
    window.dispatchEvent(new CustomEvent("majo:flash", { detail: message }));
  };

  useEffect(() => {
    if (!open) return;
    void load()
      .then((list) => {
        for (const plugin of list) void mountNative(plugin, flash);
      })
      .catch(() => setPlugins([]));
    const timer = window.setInterval(() => {
      void load().then((list) => {
        for (const plugin of list) void mountNative(plugin, flash);
      });
    }, 10000);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    return () => {
      for (const dispose of disposersRef.current) dispose();
      disposersRef.current = [];
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
          {plugins?.map((plugin) => (
            <div key={plugin.name} className="side-item">
              <button
                type="button"
                className="side-refresh plugin-open"
                title={plugin.module ? "native module (component in slots)" : "hosted page"}
                onClick={() => {
                  if (plugin.module && loadedModules.has(plugin.name)) {
                    // native already mounted; its own section shows up in the sidebar
                    flash(plugin.title || plugin.name + " is mounted — see its sidebar section");
                  } else if (plugin.module && !loadedModules.has(plugin.name)) {
                    void mountNative(plugin, flash);
                  } else {
                    openPlugin?.(plugin.name, plugin.url);
                  }
                }}
              >
                ▶ {plugin.title || plugin.name}
                {plugin.module && (
                  <span className={"meta native-dot " + (nativeStates[plugin.name] ?? "pending")}>
                    {" "}
                    {nativeStates[plugin.name] === "error"
                      ? "· load failed"
                      : nativeStates[plugin.name] === "mounted"
                        ? "· native ✓"
                        : "· native"}
                  </span>
                )}
              </button>
            </div>
          ))}
          {plugins && plugins.length > 0 && (
            <button type="button" className="side-refresh" onClick={() => void load()}>
              refresh
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
