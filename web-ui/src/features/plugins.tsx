import { useEffect, useState } from "react";
import { api } from "../api";
import type { PluginInfo } from "../types";
import type { Feature, SectionProps } from "../slots";

// Mounted plugin frontends: the backend hosts each plugin jar's
// static-web/<name>/ assets under /plugins/<name>/; this section lists what
// is mounted and opens the chosen plugin in the main pane.
function PluginsMenu({ openPlugin }: { openPlugin?: (name: string, url: string) => void }) {
  const [open, setOpen] = useState(false);
  const [plugins, setPlugins] = useState<PluginInfo[] | null>(null);

  const load = async () => {
    const index = await api.plugins();
    setPlugins(index.plugins || []);
  };

  useEffect(() => {
    if (!open) return;
    void load().catch(() => setPlugins([]));
    const timer = window.setInterval(() => void load().catch(() => {}), 10000);
    return () => window.clearInterval(timer);
  }, [open]);

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
            <button
              type="button"
              key={plugin.name}
              className="side-refresh plugin-open"
              onClick={() => openPlugin?.(plugin.name, plugin.url)}
            >
              ▶ {plugin.name}
            </button>
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
