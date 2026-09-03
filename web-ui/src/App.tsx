import { useEffect, useRef, type ReactNode } from "react";
import { SlotRoot, useSlots, type CommandSeat, type RailProps } from "./slots";
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
      <li className={style ?? "group"} key={event.kind + event.seq}>
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
  const { rails, sidebarSections, commands } = useSlots();
  const frameRef = useRef<HTMLIFrameElement | null>(null);

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

  const flash = (message: string) => actions.setNotice(message);

  // postMessage bridge: hosted plugin pages (source majo-plugin) may drive
  // the host — open a session, run a task, start a new chat, flash notices.
  useEffect(() => {
    const onFlash = (event: Event) => {
      const detail = (event as CustomEvent<string>).detail;
      if (typeof detail === "string") flash(detail);
    };
    window.addEventListener("majo:flash", onFlash);
    return () => window.removeEventListener("majo:flash", onFlash);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      const frame = frameRef.current;
      if (!frame || event.source !== frame.contentWindow) return;
      const msg = event.data as { source?: string; type?: string } & Record<string, unknown>;
      if (!msg || msg.source !== "majo-plugin") return;
      switch (msg.type) {
        case "flash":
          if (typeof msg.message === "string") flash(msg.message);
          break;
        case "newChat":
          actions.newChat();
          break;
        case "close":
          actions.closePlugin();
          break;
        case "sendTask":
          if (typeof msg.task === "string") void actions.runTask(msg.task);
          break;
        case "openSession":
          if (typeof msg.sessionId === "string") void actions.selectSession(msg.sessionId);
          break;
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actions]);

  const runCommand = async (raw: string): Promise<void> => {
    const tokens = raw.trim().slice(1).split(/\s+/);
    const name = (tokens[0] || "").toLowerCase();
    const def = commands.find((candidate) =>
      candidate.names.some((alias) => alias.toLowerCase() === name)
    );
    if (!def) {
      flash("unknown command /" + name + " — try /help");
      return;
    }
    const seat: CommandSeat = {
      state,
      async run(action) {
        try {
          await action(actions);
        } catch (error) {
          flash(String(error));
        }
      },
      flash,
      commands,
    };
    try {
      const output = await def.run(seat, tokens.slice(1));
      if (typeof output === "string") flash(output);
    } catch (error) {
      flash(String(error));
    }
  };

  const send = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (state.input.trim().startsWith("/")) {
      void runCommand(state.input);
    } else {
      void actions.sendTask();
    }
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
            <div
              key={s.id}
              className={"session-row" + (s.id === state.sessionId ? " active" : "")}
            >
              <button
                type="button"
                className="session"
                onClick={() => void actions.selectSession(s.id)}
              >
                <span className="title">{s.title || "Untitled " + s.id.slice(0, 8)}</span>
                <span className="meta">{s.eventCount} events</span>
              </button>
              <span className="session-ops">
                <button
                  type="button"
                  title="rename"
                  disabled={state.busy}
                  onClick={(event) => {
                    event.stopPropagation();
                    const name = window.prompt("Session title", s.title || "");
                    if (name !== null) void actions.renameSession(s.id, name);
                  }}
                >
                  ✎
                </button>
                <button
                  type="button"
                  title="delete"
                  disabled={state.busy}
                  onClick={(event) => {
                    event.stopPropagation();
                    if (window.confirm("Delete this session and its log?")) {
                      void actions.deleteSession(s.id);
                    }
                  }}
                >
                  ✕
                </button>
              </span>
            </div>
          ))}
        </nav>
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
          <div id="plugin-pane">
            <div id="plugin-toolbar">
              <button type="button" onClick={actions.closePlugin}>
                ← back to chat
              </button>
              <strong>{state.pluginView.name}</strong>
            </div>
            <iframe
              ref={frameRef}
              title={state.pluginView.name}
              src={state.pluginView.url}
              className="plugin-frame"
              sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
            />
          </div>
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
                if (state.input.trim().startsWith("/")) {
                  void runCommand(state.input);
                } else {
                  void actions.sendTask();
                }
              }
            }}
          />
          <button id="send" type="submit" disabled={state.busy}>
            Send
          </button>
        </form>
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
