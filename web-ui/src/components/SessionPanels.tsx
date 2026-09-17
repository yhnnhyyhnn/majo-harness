import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import type { JobInfo, ScheduleInfo } from "../types";
import type { useChat } from "../useChat";

type ChatState = ReturnType<typeof useChat>["state"];

/** Shared fetch pattern: re-read when the session changes and when a turn finishes. */
function useSessionCatalog<T>(
  sessionId: string | null,
  busy: boolean,
  fetcher: (id: string) => Promise<T>
): T | null {
  const [data, setData] = useState<T | null>(null);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  useEffect(() => {
    if (!sessionId) {
      setData(null);
      return;
    }
    let cancelled = false;
    void fetcherRef
      .current(sessionId)
      .then((value) => {
        if (!cancelled) setData(value);
      })
      .catch(() => {
        if (!cancelled) setData(null);
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId, busy]);
  return data;
}

const STATE_DOT: Record<string, string> = {
  running: "🔄",
  completed: "✅",
  failed: "❌",
  killed: "🛑",
};

/** Session header action: background jobs (dsh ui-jobs JobListAction). */
export function JobsButton({ state }: { state: ChatState }) {
  const [open, setOpen] = useState(false);
  const index = useSessionCatalog(state.sessionId, state.busy, api.jobs.bind(api));
  const jobs: JobInfo[] = index?.jobs ?? [];
  const running = jobs.filter((job) => job.state === "running").length;

  if (!state.sessionId || jobs.length === 0) return null;
  return (
    <span className="header-catalog">
      <button
        type="button"
        className="header-action"
        title="background jobs"
        onClick={() => setOpen(!open)}
      >
        ⚙ jobs{running > 0 ? ` (${running}▲)` : ` (${jobs.length})`}
      </button>
      {open && (
        <div className="header-popover">
          {jobs.map((job) => (
            <div key={job.id} className="catalog-row">
              <span>{STATE_DOT[job.state] ?? "•"}</span>
              <span className="catalog-title">{job.id}</span>
              <span className="meta catalog-detail">
                {job.script}
                {job.exitCode != null ? ` · exit ${job.exitCode}` : ""}
              </span>
            </div>
          ))}
        </div>
      )}
    </span>
  );
}

/** Session header action: scheduled reminders (dsh ui-schedule ScheduleCatalogAction). */
export function ScheduleCatalog({ state }: { state: ChatState }) {
  const [open, setOpen] = useState(false);
  const index = useSessionCatalog(
    state.sessionId,
    state.busy,
    api.schedules.bind(api)
  );
  const schedules: ScheduleInfo[] = index?.schedules ?? [];

  if (!state.sessionId || schedules.length === 0) return null;
  return (
    <span className="header-catalog">
      <button
        type="button"
        className="header-action"
        title="scheduled reminders"
        onClick={() => setOpen(!open)}
      >
        ⏰ {schedules.length}
      </button>
      {open && (
        <div className="header-popover">
          {schedules.map((schedule) => (
            <div key={schedule.id} className="catalog-row">
              <span className="catalog-title">{schedule.id}</span>
              <span className="meta catalog-detail">
                {new Date(schedule.dueAtMs).toLocaleString()}
                {schedule.intervalSeconds
                  ? ` · every ${schedule.intervalSeconds}s`
                  : ""}{" "}
                · {schedule.prompt}
              </span>
            </div>
          ))}
        </div>
      )}
    </span>
  );
}

/**
 * Context pressure meter (dsh ui-conversation ContextMeter): estimated token
 * use vs the compaction budget; the host /compact command collapses history
 * when it climbs.
 */
export function ContextMeter({ state }: { state: ChatState }) {
  const snapshot = useSessionCatalog(
    state.sessionId,
    state.busy,
    api.context.bind(api)
  );
  if (
    !state.sessionId ||
    !snapshot ||
    !snapshot.available ||
    snapshot.estimatedTokens == null
  ) {
    return null;
  }
  const pressure = snapshot.pressure ?? 0;
  const percent = Math.round(pressure * 100);
  const hot = pressure >= 0.8;
  return (
    <span
      id="context-meter"
      className={hot ? "hot" : undefined}
      title={
        `context ≈ ${snapshot.estimatedTokens} / ${snapshot.budget} tokens` +
        (hot ? " — consider /compact" : "")
      }
    >
      <span className="meter-bar">
        <span className="meter-fill" style={{ width: `${percent}%` }} />
      </span>
      <span className="meta">{percent}%</span>
    </span>
  );
}
