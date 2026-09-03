import { useEffect, useState } from "react";
import { api } from "../api";
import type { Info } from "../types";
import type { Feature } from "../slots";

function SettingsPanel() {
  const [open, setOpen] = useState(false);
  const [info, setInfo] = useState<Info | null>(null);

  useEffect(() => {
    if (open && info === null) {
      void api.info().then(setInfo).catch(() => setInfo(null));
    }
  }, [open, info]);

  return (
    <div className="side-section">
      <button type="button" className="side-section-head" onClick={() => setOpen(!open)}>
        Settings <span className="caret">{open ? "▾" : "▸"}</span>
      </button>
      {open && (
        <div className="side-section-body">
          <div className="side-item">
            <span className="meta">version</span>
            <div>majo {info?.version ?? "…"}</div>
          </div>
          <div className="side-item">
            <span className="meta">llm models</span>
            <div>{(info?.models || []).join(", ") || "—"}</div>
          </div>
          <div className="side-item">
            <span className="meta">tools</span>
            <div>{(info?.tools || []).join(", ") || "—"}</div>
          </div>
          <div className="side-item">
            <span className="meta">skills</span>
            <div>{info?.skills ?? 0} mounted</div>
          </div>
          <div className="meta">model picker lives in the chat header</div>
        </div>
      )}
    </div>
  );
}

export const settingsFeature: Feature = {
  id: "settings",
  register(context) {
    context.addSidebarSection("settings", () => <SettingsPanel />);
  },
};
