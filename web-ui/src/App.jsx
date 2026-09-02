import { useCallback, useEffect, useMemo, useRef, useState } from "react";

// ============ markdown-lite (safe) ============

const esc = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])
  );

const prettyJson = (raw) => {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
};

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

/** Renders code fences, headings, lists, links, inline code and emphasis safely. */
function MarkdownText({ text }) {
  const inline = (input) =>
    input
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/\*([^*]+)\*/g, "<em>$1</em>")
      .replace(
        /\[([^\]]+)\]\((https?:[^)\s]+)\)/g,
        '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>'
      );

  const html = useMemo(() => {
    const source = String(text ?? "");
    const parts = source.split(/```/);
    let out = "";
    for (let i = 0; i < parts.length; i++) {
      if (i % 2 === 1) {
        const raw = esc(parts[i]).replace(/^[a-zA-Z0-9_-]+\n/, "").replace(/\n$/, "");
        out += '<pre class="code"><code>' + raw + "</code></pre>";
        continue;
      }
      const lines = esc(parts[i]).split("\n");
      const rendered = [];
      let list = null;
      for (const line of lines) {
        const h = line.match(/^(#{1,4})\s+(.*)$/);
        const ul = line.match(/^\s*[-*]\s+(.*)$/);
        const ol = line.match(/^\s*\d+[.)]\s+(.*)$/);
        const quote = line.match(/^\s*>\s?(.*)$/);
        const rule = /^\s*---+\s*$/.test(line);
        if (rule) {
          if (list) { rendered.push(list + "</ul>"); list = null; }
          rendered.push("<hr>");
        } else if (h) {
          if (list) { rendered.push(list + "</ul>"); list = null; }
          const level = h[1].length;
          const content = inline(h[2]);
          rendered.push(`<h${level}>${content}</h${level}>`);
        } else if (ul) {
          list = list ?? "<ul>";
          list += "<li>" + inline(ul[1]) + "</li>";
        } else if (ol) {
          list = list ?? "<ol>";
          list += "<li>" + inline(ol[1]) + "</li>";
        } else if (quote) {
          if (list) { rendered.push(list + "</ul>"); list = null; }
          rendered.push("<blockquote>" + inline(quote[1]) + "</blockquote>");
        } else if (line.trim().length === 0) {
          if (list) { rendered.push(list + "</ul>"); list = null; }
          rendered.push("<br>");
        } else {
          if (list) { rendered.push(list + "</ul>"); list = null; }
          rendered.push(inline(line));
        }
      }
      if (list) rendered.push(list + "</ul>");
      out += rendered.join("\n");
    }
    return out;
  }, [text]);

  return <div className="bubble rich" dangerouslySetInnerHTML={{ __html: html }} />;
}

// ============ primitives ============

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const area = document.createElement("textarea");
    area.value = text;
    document.body.appendChild(area);
    area.select();
    document.execCommand("copy");
    area.remove();
  }
}

function IconActions({ text }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    await copyText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 1200);
  };
  return (
    <span className="icon-actions">
      <button type="button" title="copy" onClick={copy}>
        {copied ? "✓ copied" : "⧉ copy"}
      </button>
    </span>
  );
}

// ============ messages ============

function AssistantMessage({ event, streaming }) {
  return (
    <div className="msg">
      <MarkdownText text={event.content} />
      {!streaming && <IconActions text={event.content} />}
    </div>
  );
}

function UserMessage({ event }) {
  return <div className="bubble">{event.content}</div>;
}

function ToolCallRow({ event }) {
  return (
    <div className="tool-block">
      <div className="tool-head">
        <span className="tool-name">tool · {event.toolCalls.map((c) => c.name).join(", ")}</span>
      </div>
      {event.toolCalls.map((call, index) => (
        <pre className="code args" key={call.name + index}>
          {prettyJson(call.arguments || "{}")}
        </pre>
      ))}
    </div>
  );
}

function ToolResultRow({ event }) {
  return (
    <div className="tool-block result">
      <span className={"dot " + (event.ok ? "ok" : "err")} />
      <span className="tool-name">{event.toolName}</span>
      <span className="result-text">{event.content ?? ""}</span>
    </div>
  );
}

function MetaLine({ children }) {
  return <div className="meta-line">{children}</div>;
}

// ============ conversation ============

function ChatView({ events, live }) {
  const listRef = useRef(null);
  const rows = [];
  let last = -1;
  for (const event of events) {
    if (event.seq != null && typeof event.seq === "number") {
      if (event.seq <= last) continue;
      last = event.seq;
    }
    switch (event.kind) {
      case "TURN_START":
      case "TURN_END":
        break;
      case "REQUEST_HEADER":
        rows.push(
          <li className="group meta" key={"h" + event.seq}>
            <MetaLine>
              model {event.model} · tools [{(event.toolNames || []).join(", ")}]
            </MetaLine>
          </li>
        );
        break;
      case "USER_MESSAGE":
        rows.push(
          <li className="message user" key={"u" + event.seq}>
            <UserMessage event={event} />
          </li>
        );
        break;
      case "ASSISTANT_MESSAGE":
        if (event.toolCalls && event.toolCalls.length) {
          rows.push(
            <li className="group tool" key={"tc" + event.seq}>
              <ToolCallRow event={event} />
            </li>
          );
        } else if (event.content != null) {
          rows.push(
            <li className="message" key={"m" + event.seq}>
              <AssistantMessage event={event} streaming={false} />
            </li>
          );
        }
        break;
      case "TOOL_RESULT":
        rows.push(
          <li className="group tool" key={"tr" + event.seq}>
            <ToolResultRow event={event} />
          </li>
        );
        break;
    }
  }
  if (live !== null) {
    rows.push(
      <li className="message streaming" key="live">
        <AssistantMessage event={{ content: live || "…" }} streaming />
      </li>
    );
  }
  useEffect(() => {
    const node = listRef.current;
    if (node) node.scrollTop = node.scrollHeight;
  }, [events.length, live, last]);
  return (
    <ol id="conversation" ref={listRef}>
      {rows}
    </ol>
  );
}

// ============ app ============

export default function App() {
  const [sessions, setSessions] = useState([]);
  const [sessionId, setSessionId] = useState(null);
  const [events, setEvents] = useState([]);
  const [title, setTitle] = useState("New chat");
  const [model, setModel] = useState(null);
  const [models, setModels] = useState([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [offline, setOffline] = useState(false);
  const [live, setLive] = useState(null);
  const [approvals, setApprovals] = useState([]);
  const [question, setQuestion] = useState(null);
  const [qInput, setQInput] = useState("");
  const sourceRef = useRef(null);

  const loadModel = useCallback(async () => {
    try {
      const state = await api("/api/settings/model");
      setModels(state.models || []);
      setModel(state.model);
    } catch {
      // settings endpoint absent on older profiles: keep defaults
    }
  }, []);

  const changeModel = async (event) => {
    const next = event.target.value;
    try {
      const state = await api("/api/settings/model", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ model: next }),
      });
      setModel(state.model);
    } catch (error) {
      console.error("model switch failed", error);
    }
  };

  const loadSessions = useCallback(async () => {
    const index = await api("/api/sessions");
    setSessions([...(index.sessions || [])].reverse());
    setOffline(false);
    return index.sessions || [];
  }, []);

  const closeStream = () => {
    const es = sourceRef.current;
    if (es) {
      sourceRef.current = null;
      es.close();
    }
  };

  const refresh = useCallback(async () => {
    try {
      const all = await loadSessions();
      let id = sessionId;
      if (!id && all.length) id = all[all.length - 1].id;
      if (id) {
        setSessionId(id);
        const detail = await api("/api/sessions/" + encodeURIComponent(id));
        setTitle(detail.title || "New chat");
        setEvents(detail.events || []);
      } else {
        setEvents([]);
      }
    } catch (error) {
      setOffline(true);
      console.error("backend unreachable", error);
    }
  }, [sessionId, loadSessions]);

  useEffect(() => {
    refresh();
    loadModel();
  }, [refresh, loadModel]);

  const push = (event) => setEvents((prev) => [...prev, event]);

  const select = async (id) => {
    closeStream();
    setSessionId(id);
    const detail = await api("/api/sessions/" + encodeURIComponent(id));
    setTitle(detail.title || "New chat");
    setEvents(detail.events || []);
    setApprovals([]);
    setQuestion(null);
    loadSessions();
  };

  const decide = async (id, granted) => {
    try {
      await api("/api/approvals/" + encodeURIComponent(id), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ decision: granted ? "allow" : "reject" }),
      });
    } catch (error) {
      console.error("approval decision failed", error);
    }
    setApprovals((prev) => prev.filter((a) => a.id !== id));
  };

  const answerAsk = async () => {
    if (!question) return;
    const id = question.id;
    try {
      await api("/api/questions/" + encodeURIComponent(id), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ answer: qInput }),
      });
    } catch (error) {
      console.error("answer failed", error);
    }
    setQuestion(null);
    setQInput("");
  };


  const send = async (formEvent) => {
    formEvent.preventDefault();
    const task = input.trim();
    if (!task || busy) return;
    setBusy(true);
    setLive("");
    let es;
    try {
      let id = sessionId;
      if (!id) {
        const created = await api("/api/sessions", { method: "POST" });
        id = created.id;
        setSessionId(id);
      }
      push({ seq: -Date.now(), kind: "USER_MESSAGE", content: task });
      const url = "/api/turn/stream?sessionId=" + encodeURIComponent(id)
        + "&task=" + encodeURIComponent(task);
      es = new EventSource(url);
      sourceRef.current = es;
      es.addEventListener("log", (e) => push(JSON.parse(e.data)));
      es.addEventListener("chunk", (e) => {
        const frame = JSON.parse(e.data);
        setLive((prev) => (prev || "") + frame.text);
      });
      es.addEventListener("done", async (e) => {
        const frame = JSON.parse(e.data);
        setLive(null);
        push({ seq: 1e12, kind: "ASSISTANT_MESSAGE", content: frame.answer });
        const all = await loadSessions();
        const mine = all.find((s) => s.id === id);
        if (mine) setTitle(mine.title || "New chat");
        closeStream();
        setBusy(false);
        setInput("");
      });
      es.addEventListener("fail", (e) => {
        const frame = JSON.parse(e.data);
        setLive(null);
        setApprovals([]);
        setQuestion(null);
        push({ seq: 1e12 + 1, kind: "ASSISTANT_MESSAGE", content: "error: " + frame.message });
        closeStream();
        setBusy(false);
      });
      es.addEventListener("approval", (e) => {
        const frame = JSON.parse(e.data);
        setApprovals((prev) => [...prev, frame]);
      });
      es.addEventListener("question", (e) => {
        const frame = JSON.parse(e.data);
        setQuestion(frame);
      });
      es.onerror = () => {
        if (sourceRef.current === es) {
          closeStream();
          setBusy(false);
        }
      };
    } catch (error) {
      setLive(null);
      push({ seq: 1e12 + 2, kind: "ASSISTANT_MESSAGE", content: "error: " + error.message });
      setBusy(false);
    }
  };

  const newChat = () => {
    closeStream();
    setSessionId(null);
    setEvents([]);
    setTitle("New chat");
    setLive(null);
    loadSessions().catch(() => {});
  };

  const retry = () => {
    setOffline(false);
    refresh();
  };

  return (
    <>
      {offline && (
        <div id="banner" role="alert">
          Cannot reach the harness backend — is `java -jar majo-web…` running?
          <button type="button" onClick={retry}>Retry</button>
        </div>
      )}
      <aside id="sidebar">
        <header className="brand"><strong>majo</strong><span>harness</span></header>
        <button id="new-chat" type="button" onClick={newChat}>+ New chat</button>
        <nav id="session-list">
          {sessions.length === 0 && <div className="meta">no sessions yet</div>}
          {sessions.map((s) => (
            <button
              key={s.id}
              type="button"
              className={"session" + (s.id === sessionId ? " active" : "")}
              onClick={() => select(s.id)}
            >
              <span className="title">{s.title || "Untitled " + s.id.slice(0, 8)}</span>
              <span className="meta">{s.eventCount} events</span>
            </button>
          ))}
        </nav>
      </aside>
      <main>
        <header id="chat-header">
          <span id="current-title">{title}</span>
          <label className="model-picker">
            model
            <select value={model || ""} onChange={changeModel} disabled={busy}>
              {models.length === 0 && <option value="">—</option>}
              {models.map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </select>
          </label>
        </header>
        <ChatView events={events} live={busy ? live : null} />
        {(approvals.length > 0 || question) && (
          <div id="approval-rail">
            {approvals.map((approval) => (
              <div className="approval-card" key={approval.id}>
                <div className="approval-head"><span className="dot pending" /> waiting for approval</div>
                <div className="approval-body">{approval.summary}</div>
                <div className="approval-actions">
                  <button type="button" onClick={() => decide(approval.id, false)}>Reject</button>
                  <button type="button" className="primary" onClick={() => decide(approval.id, true)}>Allow once</button>
                </div>
              </div>
            ))}
            {question && (
              <div className="approval-card question">
                <div className="approval-head"><span className="dot pending" /> the agent asks</div>
                <div className="approval-body">{question.text}</div>
                <form
                  className="question-form"
                  onSubmit={(e) => {
                    e.preventDefault();
                    answerAsk();
                  }}
                >
                  <input
                    value={qInput}
                    onChange={(e) => setQInput(e.target.value)}
                    placeholder="your answer…"
                    autoFocus
                  />
                  <button type="submit">Send</button>
                </form>
              </div>
            )}
          </div>
        )}
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
          <button id="send" type="submit" disabled={busy}>Send</button>
        </form>
        <div id="busy" hidden={!busy}><span className="spinner" /> running…</div>
        <footer id="status" className={offline ? "error" : "online"}>
          {offline ? "offline" : "online"}
        </footer>
      </main>
    </>
  );
}
