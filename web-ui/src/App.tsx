import { useEffect, type ReactNode } from "react";
import { SlotRoot, useSlots, type RailProps } from "./slots";
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

function Conversation({ events, live }: { events: EventFrame[]; live: string | null }) {
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
      <li className={style ?? "group"} key={event.kind + event.seq}>
        {render({ event })}
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
  const { rails } = useSlots();

  useEffect(() => {
    void actions.loadInitial();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const send = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    void actions.sendTask();
  };

  const railProps: RailProps = {
    approvals: state.approvals,
    question: state.question,
    qInput: state.qInput,
    onQInput: actions.setQInput,
    onDecide: (id, granted) => void actions.decide(id, granted),
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
      <aside id="sidebar">
        <header className="brand">
          <strong>majo</strong>
          <span>harness</span>
        </header>
        <button id="new-chat" type="button" onClick={actions.newChat}>
          + New chat
        </button>
        <nav id="session-list">
          {state.sessions.length === 0 && <div className="meta">no sessions yet</div>}
          {state.sessions.map((s) => (
            <button
              key={s.id}
              type="button"
              className={"session" + (s.id === state.sessionId ? " active" : "")}
              onClick={() => void actions.selectSession(s.id)}
            >
              <span className="title">{s.title || "Untitled " + s.id.slice(0, 8)}</span>
              <span className="meta">{s.eventCount} events</span>
            </button>
          ))}
        </nav>
      </aside>
      <main>
        <header id="chat-header">
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
        </header>
        {railNodes.length > 0 && (
          <div id="rail-region">
            {railNodes.map((item) => (
              <div key={item.id}>{item.node}</div>
            ))}
          </div>
        )}
        <Conversation events={state.events} live={state.busy ? state.live : null} />
        <form id="composer" onSubmit={send}>
          <textarea
            id="input"
            rows={1}
            value={state.input}
            placeholder="Type a task… (Enter to send, Shift+Enter for a new line)"
            onChange={(e) => actions.setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                void actions.sendTask();
              }
            }}
          />
          <button id="send" type="submit" disabled={state.busy}>
            Send
          </button>
        </form>
        <div id="busy" hidden={!state.busy}>
          <span className="spinner" /> running…
        </div>
        <footer id="status" className={state.offline ? "error" : "online"}>
          {state.offline ? "offline" : "online"}
        </footer>
      </main>
    </>
  );
}
