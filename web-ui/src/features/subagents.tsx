import { useEffect, useState } from "react";
import { api } from "../api";
import type { SubagentRun } from "../types";
import type { Feature } from "../slots";

function SubagentsPanel() {
  const [open, setOpen] = useState(false);
  const [runs, setRuns] = useState<SubagentRun[] | null>(null);

  const load = async () => {
    const index = await api.subagents();
    setRuns(index.runs || []);
  };

  useEffect(() => {
    if (open && runs === null) {
      void load().catch(() => setRuns([]));
    }
  }, [open, runs]);

  return (
    <div className="side-section">
      <button type="button" className="side-section-head" onClick={() => setOpen(!open)}>
        Subagents {runs ? `(${runs.length})` : ""}{" "}
        <span className="caret">{open ? "▾" : "▸"}</span>
      </button>
      {open && (
        <div className="side-section-body">
          {runs === null && <div className="meta">loading…</div>}
          {runs && runs.length === 0 && <div className="meta">no delegations yet</div>}
          {runs?.map((run, i) => (
            <div className="side-item" key={`${run.atMillis}-${i}`}>
              <div className="side-item-title">
                <span className={`status status-${run.status}`}>{run.status}</span>{" "}
                <span title={run.task}>{run.task}</span>
              </div>
              {run.detail && <div className="meta">{run.detail}</div>}
              <div className="meta">{new Date(run.atMillis).toLocaleTimeString()}</div>
            </div>
          ))}
          <button type="button" className="side-refresh" onClick={() => void load()}>
            refresh
          </button>
        </div>
      )}
    </div>
  );
}

export const subagentsFeature: Feature = {
  id: "subagents",
  register(context) {
    context.addSidebarSection("subagents", () => <SubagentsPanel />);
  },
};
