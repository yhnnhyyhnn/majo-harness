import { useCallback, useEffect, useRef, useState } from "react";

const esc = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])
  );

async function api(path, options) {
  const response = await fetch(path, options);
  let body;
  try {
    body = await response.json();
  } catch {
    throw new Error("bad response from server (HTTP " + response.status + ")");
  }
  if (!response.ok) throw new Error(body.error || "HTTP " + response.status);
  return body;
}

const shortId = (id) => (id.length > 8 ? id.slice(0, 8) : id);

function Bubbles({ events }) {
  const rows = [];
  for (const event of events) {
    switch (event.kind) {
      case "TURN_START":
      case "TURN_END":
        break;
      case "USER_MESSAGE":
        rows.push(
          <li className="message user" key={event.seq}>
            <div className="bubble">{event.content}</div>
          </li>
        );
        break;
      case "ASSISTANT_MESSAGE":
        if (event.toolCalls && event.toolCalls.length) {
          for (const call of event.toolCalls) {
            rows.push(
              <li className="meta-line" key={event.seq + "-meta"}>
                assistant requested tool <b>{call.name}</b>
              </li>
            );
            rows.push(
              <li className="tool-line" key={event.seq + "-args"}>
                <span className="chip" dangerouslySetInnerHTML={{ __html: esc(call.arguments || "{}") }} />
              </li>
            );
          }
        } else if (event.content != null) {
          rows.push(
            <li className="message" key={event.seq}>
              <div className="bubble">{event.content}</div>
            </li>
          );
        }
        break;
      case "TOOL_RESULT":
        rows.push(
          <li className="tool-line" key={event.seq}>
            <span className="chip">
              <span className={"dot " + (event.ok ? "ok" : "err")} />
              {event.toolName} → {event.content ?? ""}
            </span>
          </li>
        );
        break;
      case "REQUEST_HEADER":
        rows.push(
          <li className="meta-line" key={event.seq}>
            model {event.model} · tools [{(event.toolNames || []).join(", ")}]
          </li>
        );
        break;
    }
  }
  return rows;
}

export default function App() {
  const [sessions, setSessions] = useState([]);
  const [sessionId, setSessionId] = useState(null);
  const [events, setEvents] = useState([]);
  const [title, setTitle] = useState("New chat");
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [offline, setOffline] = useState(false);
  const listRef = useRef(null);

  const scrollDown = () => {
    const node = listRef.current;
    if (node) node.scrollTop = node.scrollHeight;
  };

  const refresh = useCallback(async () => {
    try {
      const index = await api("/api/sessions");
      setSessions([...(index.sessions || [])].reverse());
      setOffline(false);
      let id = sessionId;
      if (!id && (index.sessions || []).length) {
        id = index.sessions[index.sessions.length - 1].id;
        setSessionId(id);
      }
      if (id) {
        const detail = await api("/api/sessions/" + encodeURIComponent(id));
        setTitle(detail.title || "New chat");
        setEvents(detail.events || []);
      }
    } catch (error) {
      setOffline(true);
      console.error("backend unreachable", error);
    }
  }, [sessionId]);

  useEffect(() => {
    refresh().then(scrollDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const select = async (id) => {
    setSessionId(id);
    const detail = await api("/api/sessions/" + encodeURIComponent(id));
    setTitle(detail.title || "New chat");
    setEvents(detail.events || []);
    scrollDown();
  };

  const send = async (event) => {
    event.preventDefault();
    const task = input.trim();
    if (!task || busy) return;
    setBusy(true);
    try {
      const data = await api("/api/turn", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sessionId, task }),
      });
      setSessionId(data.sessionId);
      setEvents(data.events || []);
      await refresh();
      setInput("");
      setOffline(false);
    } catch (error) {
      setEvents((prev) => [...prev, { seq: Date.now(), kind: "ASSISTANT_MESSAGE", content: "error: " + error.message }]);
    } finally {
      setBusy(false);
      scrollDown();
    }
  };

  const newChat = () => {
    setSessionId(null);
    setEvents([]);
    setTitle("New chat");
    refresh();
  };

  return (
    <>
      {offline && (
        <div id="banner" role="alert">
          Cannot reach the harness backend — is `java -jar majo-web…` running?
        </div>
      )}
      <aside id="sidebar">
        <header className="brand">
          <strong>majo</strong>
          <span>harness</span>
        </header>
        <button id="new-chat" type="button" onClick={newChat}>
          + New chat
        </button>
        <nav id="session-list">
          {sessions.length === 0 && <div className="meta">no sessions yet</div>}
          {sessions.map((s) => (
            <button
              key={s.id}
              type="button"
              className={"session" + (s.id === sessionId ? " active" : "")}
              onClick={() => select(s.id)}
            >
              <span className="title">{s.title || "Untitled " + shortId(s.id)}</span>
              <span className="meta">{s.eventCount} events</span>
            </button>
          ))}
        </nav>
      </aside>
      <main>
        <header id="chat-header">
          <span id="current-title">{title}</span>
          <span id="model-badge" className="badge">model: profile</span>
        </header>
        <ol id="conversation" ref={listRef}>
          <Bubbles events={events} />
        </ol>
        <form id="composer" onSubmit={send}>
          <textarea
            id="input"
            rows="1"
            value={input}
            placeholder="Type a task… (Enter to send, Shift+Enter for a new line)"
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                send(e);
              }
            }}
          />
          <button id="send" type="submit" disabled={busy}>
            Send
          </button>
        </form>
        <div id="busy" hidden={!busy}>
          <span className="spinner" /> running…
        </div>
        <footer id="status" className={offline ? "error" : "online"}>
          {offline ? "offline" : "online"}
        </footer>
      </main>
    </>
  );
}
