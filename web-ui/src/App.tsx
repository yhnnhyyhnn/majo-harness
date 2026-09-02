import { useCallback, useEffect, useMemo, useRef, useState } from "react";

// ---------- shared types (mirror the /api JSON) ----------

type EventKind =
  | "TURN_START"
  | "TURN_END"
  | "USER_MESSAGE"
  | "ASSISTANT_MESSAGE"
  | "TOOL_RESULT"
  | "REQUEST_HEADER";

interface ToolCallFrame {
  name: string;
  arguments?: string;
}

interface EventFrame {
  seq: number;
  kind: EventKind;
  content?: string | null;
  toolCalls?: ToolCallFrame[];
  toolName?: string;
  ok?: boolean;
  model?: string;
  toolNames?: string[];
}

interface SessionInfo {
  id: string;
  title?: string | null;
  eventCount: number;
}

interface ApprovalFrame {
  id: string;
  summary: string;
  details?: string;
}

interface QuestionFrame {
  id: string;
  text: string;
}

// ---------- small safe markdown-lite ----------

const escapeHtml = (value: unknown): string =>
  String(value ?? "").replace(/[&<>"']/g, (c) => {
    const entities: Record<string, string> = {
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    };
    return entities[c] ?? c;
  });

const prettyJson = (raw: string): string => {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
};

async function api(path: string, options?: RequestInit): Promise<Record<string, unknown>> {
  const response = await fetch(path, options);
  let body: Record<string, unknown>;
  try {
    body = await response.json();
  } catch {
    throw new Error("bad response from server (HTTP " + response.status + ")");
  }
  if (!response.ok) throw new Error(String(body.error || "HTTP " + response.status));
  return body;
}

// ---------- rendering ----------

function MarkdownText({ text }: { text: string }) {
  const inline = (input: string): string =>
    input
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/\*([^*]+)\*/g, "<em>$1</em>")
      .replace(
        /\[([^\]]+)\]\((https?:[^)\s]+)\)/g,
        '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>'
      );

  const html = useMemo(() => {
    const parts = String(text ?? "").split(/```/);
    let out = "";
    for (let i = 0; i < parts.length; i++) {
      if (i % 2 === 1) {
        const code = escapeHtml(parts[i]).replace(/^[a-zA-Z0-9_-]+\n/, "").replace(/\n$/, "");
        out += '<pre class="code"><code>' + code + "</code></pre>";
        continue;
      }
      const lines = escapeHtml(parts[i]).split("\n");
      const rendered: string[] = [];
      let list: string | null = null;
      const flushList = () => {
        if (list) {
          rendered.push(list + "</ul>");
          list = null;
        }
      };
      for (const raw of lines) {
        const heading = raw.match(/^(#{1,4})\s+(.*)$/);
        const bullet = raw.match(/^\s*[-*]\s+(.*)$/);
        const ordered = raw.match(/^\s*\d+[.)]\s+(.*)$/);
        const quote = raw.match(/^\s*>\s?(.*)$/);
        if (/^\s*---+\s*$/.test(raw)) {
          flushList();
          rendered.push("<hr>");
        } else if (heading) {
          flushList();
          rendered.push(`<h${heading[1].length}>${inline(heading[2])}</h${heading[1].length}>`);
        } else if (bullet || ordered) {
          list = list ?? "<ul>";
          list += `<li>${inline((bullet ?? ordered)?.[1] ?? "")}</li>`;
        } else if (quote) {
          flushList();
          rendered.push("<blockquote>" + inline(quote[1]) + "</blockquote>");
        } else if (raw.trim().length === 0) {
          flushList();
          rendered.push("<br>");
        } else {
          flushList();
          rendered.push(inline(raw));
        }
      }
      flushList();
      out += rendered.join("\n");
    }
    return out;
  }, [text]);

  return <div className="bubble rich" dangerouslySetInnerHTML={{ __html: html }} />;
}

async function copyText(text: string): Promise<void> {
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

function IconActions({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    await copyText(text);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1200);
  };
  return (
    <span className="icon-actions">
      <button type="button" title="copy" onClick={() => void copy()}>
        {copied ? "✓ copied" : "⧉ copy"}
      </button>
    </span>
  );
}

function AssistantMessage({ content, streaming }: { content: string; streaming?: boolean }) {
  return (
    <div className="msg">
      <MarkdownText text={content} />
      {!streaming && <IconActions text={content} />}
    </div>
  );
}

function UserMessage({ content }: { content: string }) {
  return <div className="bubble">{content}</div>;
}

function ToolCallRow({ calls }: { calls: ToolCallFrame[] }) {
  return (
    <div className="tool-block">
      <div className="tool-head">
        <span className="tool-name">tool · {calls.map((c) => c.name).join(", ")}</span>
      </div>
      {calls.map((call, index) => (
        <pre className="code args" key={call.name + index}>
          {prettyJson(call.arguments || "{}")}
        </pre>
      ))}
    </div>
  );
}

function ToolResultRow({ event }: { event: EventFrame }) {
  return (
    <div className="tool-block result">
      <span className={"dot " + (event.ok ? "ok" : "err")} />
      <span className="tool-name">{event.toolName}</span>
      <span className="result-text">{event.content ?? ""}</span>
    </div>
  );
}

function ChatView({ events, live }: { events: EventFrame[]; live: string | null }) {
  const listRef = useRef<HTMLOListElement | null>(null);
  const rows: React.ReactNode[] = [];
  let last = -1;
  for (const event of events) {
    if (typeof event.seq === "number") {
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
            model {event.model} · tools [{(event.toolNames || []).join(", ")}]
          </li>
        );
        break;
      case "USER_MESSAGE":
        rows.push(
          <li className="message user" key={"u" + event.seq}>
            <UserMessage content={event.content ?? ""} />
          </li>
        );
        break;
      case "ASSISTANT_MESSAGE":
        if (event.toolCalls && event.toolCalls.length) {
          rows.push(
            <li className="group tool" key={"tc" + event.seq}>
              <ToolCallRow calls={event.toolCalls} />
            </li>
          );
        } else if (event.content != null) {
          rows.push(
            <li className="message" key={"m" + event.seq}>
              <AssistantMessage content={event.content} />
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
        <AssistantMessage content={live || "…"} streaming />
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

// ---------- app ----------

export default function App() {
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [events, setEvents] = useState<EventFrame[]>([]);
  const [title, setTitle] = useState("New chat");
  const [model, setModel] = useState<string | null>(null);
  const [models, setModels] = useState<string[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [offline, setOffline] = useState(false);
  const [live, setLive] = useState<string | null>(null);
  const [approvals, setApprovals] = useState<ApprovalFrame[]>([]);
  const [question, setQuestion] = useState<QuestionFrame | null>(null);
  const [qInput, setQInput] = useState("");
  const sourceRef = useRef<EventSource | null>(null);

  const loadModel = useCallback(async () => {
    try {
      const state = await api("/api/settings/model");
      setModels((state.models as string[]) || []);
      setModel((state.model as string | null) || null);
    } catch {
      // settings endpoint absent on older profiles: keep defaults
    }
  }, []);

  const changeModel = async (event: React.ChangeEvent<HTMLSelectElement>) => {
    try {
      const state = await api("/api/settings/model", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ model: event.target.value }),
      });
      setModel((state.model as string) || null);
    } catch (error) {
      console.error("model switch failed", error);
    }
  };

  const loadSessions = useCallback(async (): Promise<SessionInfo[]> => {
    const index = await api("/api/sessions");
    const all = (index.sessions as SessionInfo[]) || [];
    setSessions([...all].reverse());
    setOffline(false);
    return all;
  }, []);

  const closeStream = () => {
    const source = sourceRef.current;
    if (source) {
      sourceRef.current = null;
      source.close();
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
        setTitle((detail.title as string) || "New chat");
        setEvents((detail.events as EventFrame[]) || []);
      } else {
        setEvents([]);
      }
    } catch (error) {
      setOffline(true);
      console.error("backend unreachable", error);
    }
  }, [sessionId, loadSessions]);

  useEffect(() => {
    void refresh();
    void loadModel();
  }, [refresh, loadModel]);

  const push = (event: EventFrame) => setEvents((prev) => [...prev, event]);

  const select = async (id: string) => {
    closeStream();
    setSessionId(id);
    const detail = await api("/api/sessions/" + encodeURIComponent(id));
    setTitle((detail.title as string) || "New chat");
    setEvents((detail.events as EventFrame[]) || []);
    setApprovals([]);
    setQuestion(null);
    void loadSessions();
  };

  const decide = async (id: string, granted: boolean) => {
    try {
      await api("/api/approvals/" + encodeURIComponent(id), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ decision: granted ? "allow" : "reject" }),
      });
    } catch (error) {
      console.error("approval decision failed", error);
    }
    setApprovals((prev) => prev.filter((approval) => approval.id !== id));
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

  const send = async (formEvent: React.SyntheticEvent) => {
    formEvent.preventDefault();
    const task = input.trim();
    if (!task || busy) return;
    setBusy(true);
    setLive("");
    try {
      let id = sessionId;
      if (!id) {
        const created = await api("/api/sessions", { method: "POST" });
        id = created.id as string;
        setSessionId(id);
      }
      push({ seq: -Date.now(), kind: "USER_MESSAGE", content: task });
      const url =
        "/api/turn/stream?sessionId=" + encodeURIComponent(id) + "&task=" + encodeURIComponent(task);
      const source = new EventSource(url);
      sourceRef.current = source;

      source.addEventListener("log", (e) => push(JSON.parse((e as MessageEvent).data) as EventFrame));
      source.addEventListener("chunk", (e) => {
        const frame = JSON.parse((e as MessageEvent).data) as { text: string };
        setLive((prev) => (prev || "") + frame.text);
      });
      source.addEventListener("done", async (e) => {
        const frame = JSON.parse((e as MessageEvent).data) as { sessionId: string; answer: string };
        setLive(null);
        setApprovals([]);
        setQuestion(null);
        push({ seq: 1e12, kind: "ASSISTANT_MESSAGE", content: frame.answer });
        const all = await loadSessions();
        const mine = all.find((s) => s.id === frame.sessionId);
        if (mine) setTitle(mine.title || "New chat");
        closeStream();
        setBusy(false);
        setInput("");
      });
      source.addEventListener("fail", (e) => {
        const frame = JSON.parse((e as MessageEvent).data) as { message: string };
        setLive(null);
        setApprovals([]);
        setQuestion(null);
        push({ seq: 1e12 + 1, kind: "ASSISTANT_MESSAGE", content: "error: " + frame.message });
        closeStream();
        setBusy(false);
      });
      source.addEventListener("approval", (e) => {
        const frame = JSON.parse((e as MessageEvent).data) as ApprovalFrame;
        setApprovals((prev) => [...prev, frame]);
      });
      source.addEventListener("question", (e) => {
        const frame = JSON.parse((e as MessageEvent).data) as QuestionFrame;
        setQuestion(frame);
      });
      source.onerror = () => {
        if (sourceRef.current === source) {
          closeStream();
          setBusy(false);
        }
      };
    } catch (error) {
      setLive(null);
      push({ seq: 1e12 + 2, kind: "ASSISTANT_MESSAGE", content: "error: " + String(error) });
      setBusy(false);
    }
  };

  const newChat = () => {
    closeStream();
    setSessionId(null);
    setEvents([]);
    setTitle("New chat");
    setLive(null);
    setApprovals([]);
    setQuestion(null);
    void loadSessions().catch(() => {});
  };

  const retry = () => {
    setOffline(false);
    void refresh();
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
              onClick={() => void select(s.id)}
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
        {(approvals.length > 0 || question) && (
          <div id="approval-rail">
            {approvals.map((approval) => (
              <div className="approval-card" key={approval.id}>
                <div className="approval-head">
                  <span className="dot pending" /> waiting for approval
                </div>
                <div className="approval-body">{approval.summary}</div>
                <div className="approval-actions">
                  <button type="button" onClick={() => void decide(approval.id, false)}>Reject</button>
                  <button type="button" className="primary" onClick={() => void decide(approval.id, true)}>
                    Allow once
                  </button>
                </div>
              </div>
            ))}
            {question && (
              <div className="approval-card question">
                <div className="approval-head">
                  <span className="dot pending" /> the agent asks
                </div>
                <div className="approval-body">{question.text}</div>
                <form
                  className="question-form"
                  onSubmit={(e) => {
                    e.preventDefault();
                    void answerAsk();
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
        <ChatView events={events} live={busy ? live : null} />
        <form id="composer" onSubmit={(e) => void send(e)}>
          <textarea
            id="input"
            rows={1}
            value={input}
            placeholder="Type a task… (Enter to send, Shift+Enter for a new line)"
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                void send(e);
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
