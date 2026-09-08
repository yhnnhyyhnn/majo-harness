import { useEffect, useState } from "react";
import { api } from "../api";
import type { SubagentRun } from "../types";
import type { Feature } from "../slots";

/** Compose form state for one delegation (all optional except the task). */
interface Form {
  task: string;
  model: string;
  maxSteps: string;
  autoApprove: boolean;
  allowedTools: string;
  islands: string[];
  settingsKey: string;
  settingsValue: string;
}

const emptyForm = (): Form => ({
  task: "",
  model: "",
  maxSteps: "",
  autoApprove: false,
  allowedTools: "",
  islands: [],
  settingsKey: "",
  settingsValue: "",
});

function SubagentsPanel() {
  const [open, setOpen] = useState(false);
  const [runs, setRuns] = useState<SubagentRun[] | null>(null);
  const [models, setModels] = useState<string[]>([]);
  const [hostIslands, setHostIslands] = useState<string[]>([]);
  const [form, setForm] = useState<Form>(emptyForm());
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<string | null>(null);

  const load = async () => {
    const index = await api.subagents();
    setRuns(index.runs || []);
  };

  const loadOptions = async () => {
    try {
      const info = await api.info();
      setModels(info.models || []);
    } catch {
      setModels([]);
    }
    try {
      const islands = await api.hostIslands();
      setHostIslands(islands.islands || []);
    } catch {
      setHostIslands([]);
    }
  };

  // fetch on open, then poll so finished delegations appear without a click
  useEffect(() => {
    if (!open) return;
    void loadOptions();
    void load().catch(() => setRuns([]));
    const timer = window.setInterval(() => void load().catch(() => {}), 3000);
    return () => window.clearInterval(timer);
  }, [open]);

  const delegate = async () => {
    const task = form.task.trim();
    if (!task || busy) return;
    setBusy(true);
    setStatus(null);
    try {
      const settings =
        form.settingsKey.trim() || form.settingsValue.trim()
          ? { [form.settingsKey.trim()]: form.settingsValue.trim() }
          : undefined;
      const maxSteps = form.maxSteps.trim() ? Number(form.maxSteps) : undefined;
      const allowedTools = form.allowedTools
        .split(",")
        .map((tool) => tool.trim())
        .filter(Boolean);
      const result = await api.delegate({
        task,
        ...(form.model ? { model: form.model } : {}),
        ...(maxSteps !== undefined && Number.isFinite(maxSteps) && maxSteps >= 1 ? { maxSteps } : {}),
        ...(form.autoApprove ? { autoApprove: true } : {}),
        ...(allowedTools.length ? { allowedTools } : {}),
        ...(form.islands.length ? { islands: form.islands } : {}),
        ...(settings ? { settings } : {}),
      });
      const preview = (result.answer || "").replace(/\s+/g, " ").trim().slice(0, 140);
      setStatus(
        "child " + result.childSessionId.slice(0, 8) + " → " + (preview || "(no answer)")
      );
      setForm({ ...emptyForm(), model: form.model, autoApprove: form.autoApprove });
      void load().catch(() => {});
    } catch (error) {
      setStatus("delegate failed: " + String(error));
    } finally {
      setBusy(false);
    }
  };

  const set = <K extends keyof Form>(key: K, value: Form[K]) =>
    setForm((f) => ({ ...f, [key]: value }));

  return (
    <div className="side-section">
      <button type="button" className="side-section-head" onClick={() => setOpen(!open)}>
        Subagents {runs ? `(${runs.length})` : ""}{" "}
        <span className="caret">{open ? "▾" : "▸"}</span>
      </button>
      {open && (
        <div className="side-section-body">
          <div className="delegate-form">
            <label className="meta">
              task *
              <textarea
                rows={2}
                value={form.task}
                placeholder="e.g. summarise docs/architecture.md"
                onChange={(e) => set("task", e.target.value)}
              />
            </label>
            <div className="delegate-row">
              <label className="meta">
                model
                <select value={form.model} onChange={(e) => set("model", e.target.value)}>
                  <option value="">(harness default)</option>
                  {models.map((model) => (
                    <option key={model} value={model}>
                      {model}
                    </option>
                  ))}
                </select>
              </label>
              <label className="meta">
                maxSteps
                <input
                  type="number"
                  min={1}
                  value={form.maxSteps}
                  placeholder="unlimited"
                  onChange={(e) => set("maxSteps", e.target.value)}
                />
              </label>
              <label className="meta delegate-check">
                <input
                  type="checkbox"
                  checked={form.autoApprove}
                  onChange={(e) => set("autoApprove", e.target.checked)}
                />{" "}
                auto-approve
              </label>
            </div>
            <label className="meta">
              allowedTools (comma separated, empty = all)
              <input
                value={form.allowedTools}
                placeholder="calc, read_file"
                onChange={(e) => set("allowedTools", e.target.value)}
              />
            </label>
            {hostIslands.length > 0 && (
              <div className="meta islands-row">
                islands:
                {hostIslands.map((name) => (
                  <label key={name} className="delegate-check">
                    <input
                      type="checkbox"
                      checked={form.islands.includes(name)}
                      onChange={(e) =>
                        set(
                          "islands",
                          e.target.checked
                            ? [...form.islands, name]
                            : form.islands.filter((n) => n !== name)
                        )
                      }
                    />{" "}
                    {name}
                  </label>
                ))}
              </div>
            )}
            <div className="delegate-row">
              <label className="meta">
                settings key
                <input
                  value={form.settingsKey}
                  placeholder="agent.tag"
                  onChange={(e) => set("settingsKey", e.target.value)}
                />
              </label>
              <label className="meta">
                value
                <input
                  value={form.settingsValue}
                  placeholder="scoped"
                  onChange={(e) => set("settingsValue", e.target.value)}
                />
              </label>
            </div>
            <button
              type="button"
              className="side-refresh"
              disabled={busy || !form.task.trim()}
              onClick={() => void delegate()}
            >
              {busy ? "delegating…" : "delegate →"}
            </button>
            {status && <div className="meta delegate-status">{status}</div>}
          </div>

          {runs === null && <div className="meta">loading…</div>}
          {runs && runs.length === 0 && <div className="meta">no delegations yet</div>}
          {runs?.map((run, i) => (
            <div className="side-item" key={`${run.atMillis}-${i}`}>
              <div className="side-item-title">
                <span className={`status status-${run.status}`}>{run.status}</span>{" "}
                <span title={run.task}>{run.task}</span>
              </div>
              {run.detail && <div className="meta">{run.detail}</div>}
              {(run.model || run.maxSteps !== undefined || run.autoApprove !== undefined || run.allowedTools) && (
                <div className="meta spec">
                  {run.model && <span>model {run.model}</span>}
                  {run.maxSteps !== undefined && <span>maxSteps {run.maxSteps}</span>}
                  {run.autoApprove && <span>auto-approve</span>}
                  {run.allowedTools && <span>tools [{run.allowedTools.join(", ")}]</span>}
                </div>
              )}
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
