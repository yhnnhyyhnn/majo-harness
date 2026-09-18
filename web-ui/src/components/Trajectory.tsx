import { useMemo, useState } from "react";
import type { EventFrame } from "../types";

/**
 * The trajectory view (dsh ui-trajectory, proportionate): a turn-grouped
 * ledger of every durable event — the kinds the chat view hides (request
 * headers, approvals, compaction, bookkeeping) are exactly what this view
 * exists to show, with per-turn durations and a text filter.
 */
export function Trajectory({ events }: { events: EventFrame[] }) {
  const [filter, setFilter] = useState("");

  const turns = useMemo(() => groupTurns(events), [events]);
  const needle = filter.trim().toLowerCase();

  const visible = needle
    ? turns
        .map((turn) => ({
          ...turn,
          rows: turn.rows.filter((row) =>
            (row.text + " " + row.kind).toLowerCase().includes(needle)
          ),
        }))
        .filter((turn) => turn.rows.length > 0)
    : turns;

  return (
    <div id="trajectory">
      <div id="trajectory-bar">
        <input
          type="search"
          placeholder="Filter events…"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <span className="meta">
          {turns.length} turns · {events.length} events
        </span>
      </div>
      {visible.map((turn) => (
        <div key={turn.key} className="trajectory-turn">
          <div className="trajectory-turn-head">
            <strong>turn {turn.index}</strong>
            <span className="meta">
              {turn.rows.length} events
              {turn.durationMs >= 0 ? ` · ${formatDuration(turn.durationMs)}` : ""}
              {turn.open ? " · running" : ""}
            </span>
          </div>
          <table className="trajectory-ledger">
            <tbody>
              {turn.rows.map((row) => (
                <tr key={row.key}>
                  <td className="t-seq">{row.seq}</td>
                  <td className="t-delta">+{formatDuration(row.deltaMs)}</td>
                  <td className="t-kind">
                    <span className={"kind-badge kind-" + row.kindCss}>{row.kind}</span>
                  </td>
                  <td className="t-text">{row.text}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
      {visible.length === 0 && <div className="meta">no events match</div>}
    </div>
  );
}

interface Row {
  key: string;
  seq: number | string;
  kind: string;
  kindCss: string;
  text: string;
  ts: number | null;
  deltaMs: number;
}

interface TurnGroup {
  key: string;
  index: number;
  open: boolean;
  durationMs: number;
  rows: Row[];
}

function groupTurns(events: EventFrame[]): TurnGroup[] {
  const turns: TurnGroup[] = [];
  let current: TurnGroup | null = null;
  let index = 0;
  let lastTs: number | null = null;

  const push = (event: EventFrame, text: string, kindCss: string) => {
    if (!current) {
      current = { key: "pre-" + event.seq, index: ++index, open: true, durationMs: -1, rows: [] };
      turns.push(current);
    }
    const ts = typeof event.ts === "number" ? event.ts : null;
    const deltaMs = ts != null && lastTs != null ? Math.max(0, ts - lastTs) : 0;
    if (ts != null) lastTs = ts;
    current.rows.push({
      key: event.kind + event.seq,
      seq: typeof event.seq === "number" ? event.seq : "—",
      kind: event.kind,
      kindCss,
      text: text || "(empty)",
      ts,
      deltaMs,
    });
  };

  for (const event of events) {
    if (event.kind === "TURN_START") {
      current = {
        key: "turn-" + event.seq,
        index: ++index,
        open: true,
        durationMs: -1,
        rows: [],
      };
      turns.push(current);
      lastTs = typeof event.ts === "number" ? event.ts : lastTs;
      push(event, "", "turn");
      continue;
    }
    if (event.kind === "TURN_END") {
      push(event, "", "turn");
      if (current) {
        current.open = false;
        const start = turns.length > 0 ? firstTs(current) : null;
        const end = lastTs;
        current.durationMs = start != null && end != null ? Math.max(0, end - start) : -1;
      }
      continue;
    }
    push(event, summarize(event), kindCss(event.kind));
  }
  return turns;
}

function firstTs(turn: TurnGroup): number | null {
  for (const row of turn.rows) {
    if (row.ts != null) return row.ts;
  }
  return null;
}

function kindCss(kind: string): string {
  if (kind.startsWith("APPROVAL")) return "audit";
  if (kind === "CONTEXT_COMPACTION") return "compaction";
  if (kind === "REQUEST_HEADER") return "header";
  if (kind === "TOOL_RESULT") return "tool";
  if (kind === "USER_MESSAGE" || kind === "CONTEXT_NOTE") return "user";
  if (kind === "ASSISTANT_MESSAGE") return "assistant";
  return "bookkeeping";
}

/** One-line summary of an event for the ledger. */
function summarize(event: EventFrame): string {
  if (event.content) return clip(oneLine(event.content), 220);
  if (event.kind === "REQUEST_HEADER") {
    const tools = event.toolNames?.length ? ` tools=[${event.toolNames.join(",")}]` : "";
    return clip(`model=${event.model ?? "?"}${tools}`, 220);
  }
  if (event.kind === "TOOL_RESULT" && event.toolName) {
    return clip(`${event.toolName} → ${event.ok ? "ok" : "error"} ${event.content ?? ""}`, 220);
  }
  if (event.kind === "TODO_SET" && event.data) {
    const items = (event.data as { items?: unknown[] }).items;
    return `todo list replaced (${Array.isArray(items) ? items.length : "?"} items)`;
  }
  if (event.kind === "SCHEDULE_SET" && event.data) {
    const data = event.data as Record<string, unknown>;
    return `schedule ${data.scheduleId ?? "?"}: ${data.prompt ?? ""}`;
  }
  if (event.kind.startsWith("WORKFLOW_") && event.data) {
    const data = event.data as Record<string, unknown>;
    const run = String(data.runId ?? "?");
    if (event.kind === "WORKFLOW_START") {
      return `workflow ${String(data.content ?? "?")} [${run}] started`;
    }
    if (event.kind === "WORKFLOW_STEP") {
      return `workflow [${run}] step ${data.stepId ?? "?"}: ${data.status ?? "?"} (${
        data.durationMs ?? "?"}ms)`;
    }
    return `workflow [${run}] ${data.content ?? "?"} (${data.durationMs ?? "?"}ms)`;
  }
  if (event.data) return clip(oneLine(JSON.stringify(event.data)), 220);
  return "";
}

function oneLine(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

function clip(text: string, max: number): string {
  return text.length <= max ? text : text.slice(0, max) + "…";
}

export function formatDuration(ms: number): string {
  if (ms < 0) return "—";
  if (ms < 1000) return ms + "ms";
  if (ms < 60_000) return (ms / 1000).toFixed(1) + "s";
  return Math.floor(ms / 60_000) + "m" + Math.round((ms % 60_000) / 1000) + "s";
}
