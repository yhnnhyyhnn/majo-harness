import { useEffect, useState, type ReactNode } from "react";
import { SlotRoot, useSlots, type RailProps } from "./slots";
import { Composer } from "./components/Composer";
import { PluginFrame } from "./components/PluginFrame";
import { SessionSidebar } from "./components/SessionSidebar";
import { FEATURES } from "./features";
import type { EventFrame, EventKind } from "./types";
import { useChat } from "./useChat";

// li wrapper styles are a shell concern; inner content comes from slots.
const kindStyle: Partial<Record<EventKind, string>> = {
  USER_MESSAGE: "message user",
  ASSISTANT_MESSAGE: "message",
  REQUEST_HEADER: "group meta",
  TOOL_RESULT: "group tool",
};

function Conversation({
  events,
  live,
  onOpenSession,
  feedback,
  onRate,
}: {
  events: EventFrame[];
  live: string | null;
  onOpenSession: (id: string) => void;
  feedback: Record<number, "up" | "down">;
  onRate: (seq: number, value: "up" | "down" | null) => void;
}) {
  const { messageRenderer } = useSlots();
  const rows: ReactNode[] = [];
  let last = -1;
  for (const event of events) {
    if (typeof event.seq === "number") {
      if (event.seq <= last) continue;
      last = event.seq;
    }
    const render = messageRenderer(event.kind);
    if (!render) continue;
    const style = kindStyle[event.kind];
    rows.push(
      <li
        className={style ?? "group"}
        key={event.kind + event.seq}
        data-seq={typeof event.seq === "number" ? event.seq : undefined}
      >
        {render({
          event,
          openSession: onOpenSession,
          rate: typeof event.seq === "number" && event.seq > 0 ? feedback[event.seq] ?? null : null,
          onRate,
        })}
      </li>
    );
  }
  if (live !== null) {
    const assistant = messageRenderer("ASSISTANT_MESSAGE");
    if (assistant) {
      rows.push(
        <li className="message streaming" key="live">
          {assistant({ event: { seq: 1e12 + 5, kind: "ASSISTANT_MESSAGE", content: live || "…" }, streaming: true })}
        </li>
      );
    }
  }
  return <ol id="conversation">{rows}</ol>;
}

export default function App() {
  return (
    <SlotRoot features={FEATURES}>
      <AppShell />
    </SlotRoot>
  );
}

function AppShell() {
  const { state, actions } = useChat();
  const { rails, sidebarSections } = useSlots();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  useEffect(() => {
    void actions.loadInitial();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // idle catch-up: while a session is open and not busy, poll events newer
  // than our cursor (covers other tabs / child runs finishing off-stream)
  useEffect(() => {
    if (!state.sessionId || state.busy) return;
    const timer = window.setInterval(() => void actions.syncEvents(), 12000);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.sessionId, state.busy]);

  // native plugin modules and hosted pages surface notices through window
  // events; the host turns them into the shared notice line
  useEffect(() => {
    const onFlash = (event: Event) => {
      const detail = (event as CustomEvent<string>).detail;
      if (typeof detail === "string") actions.setNotice(detail);
    };
    window.addEventListener("majo:flash", onFlash);
    return () => window.removeEventListener("majo:flash", onFlash);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const railProps: RailProps = {
    approvals: state.approvals,
    question: state.question,
    qInput: state.qInput,
    onQInput: actions.setQInput,
    onDecide: (id, granted) => void actions.decide(id, granted),
    onDecideAll: (granted) => {
      const ids = state.approvals.map((approval) => approval.id);
      for (const id of ids) void actions.decide(id, granted);
    },
    onAnswerAsk: () => void actions.answerAsk(),
  };
  const railNodes = rails
    .map((rail) => ({ id: rail.id, node: rail.render(railProps) }))
    .filter((item) => item.node !== null && item.node !== undefined);

  return (
    <>
      {state.offline && (
        <div id="banner" role="alert">
          Cannot reach the harness backend — is `java -jar majo-web…` running?
          <button type="button" onClick={actions.retry}>
            Retry
          </button>
        </div>
      )}
      <aside
        id="sidebar"
        className={sidebarOpen ? "open" : undefined}
        onClickCapture={() => setSidebarOpen(false)}
      >
        <header className="brand">
          <strong>majo</strong>
          <span>harness</span>
        </header>
        <button id="new-chat" type="button" onClick={actions.newChat}>
          + New chat
        </button>
        <SessionSidebar state={state} actions={actions} />
        <div id="sidebar-sections">
          {sidebarSections.map((section) => (
            <div key={section.id}>
              {section.render({
                openPlugin: (name, url) => actions.openPlugin(name, url),
              })}
            </div>
          ))}
        </div>
      </aside>
      {sidebarOpen && <div id="drawer-backdrop" onClick={() => setSidebarOpen(false)} />}
      <main>
        <header id="chat-header">
          <button
            id="sidebar-toggle"
            type="button"
            title="sessions"
            onClick={() => setSidebarOpen(true)}
          >
            ☰
          </button>
          <span id="current-title">{state.title}</span>
          <label className="model-picker">
            model
            <select
              value={state.model || ""}
              disabled={state.busy}
              onChange={(e) => void actions.changeModel(e.target.value)}
            >
              {state.models.length === 0 && <option value="">—</option>}
              {state.models.map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </select>
          </label>
          {state.sessionId && (
            <label className="model-picker">
              session
              <select
                value={state.sessionModel ?? ""}
                disabled={state.busy}
                onChange={(e) => void actions.changeSessionModel(e.target.value || null)}
              >
                <option value="">default</option>
                {state.models.map((m) => (
                  <option key={m} value={m}>
                    {m}
                  </option>
                ))}
              </select>
            </label>
          )}
        </header>
        {state.notice && <div id="notice">{state.notice}</div>}
        {state.pluginView ? (
          <PluginFrame view={state.pluginView} actions={actions} />
        ) : (
          <>
            {railNodes.length > 0 && (
              <div id="rail-region">
                {railNodes.map((item) => (
                  <div key={item.id}>{item.node}</div>
                ))}
              </div>
            )}
            <Conversation
              events={state.events}
              live={state.busy ? state.live : null}
              onOpenSession={(id) => void actions.selectSession(id)}
              feedback={state.feedback}
              onRate={(seq, value) => void actions.rate(seq, value)}
            />
            <Composer state={state} actions={actions} />
            <footer id="status" className={state.offline ? "error" : "online"}>
              {state.offline ? "offline" : "online"}
            </footer>
          </>
        )}
        <div id="busy" hidden={!state.busy}>
          <span className="spinner" /> running…
        </div>
      </main>
    </>
  );
}
