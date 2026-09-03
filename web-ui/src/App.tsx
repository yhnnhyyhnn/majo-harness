import { useEffect, type FormEvent, type ReactNode } from "react";
import type { EventFrame, ToolCallFrame } from "./types";
import { useChat } from "./useChat";

// ---------- markdown-lite ----------

const escapeHtml = (value: unknown): string =>
  String(value ?? "").replace(/[&<>"']/g, (c) => {
    const entities: Record<string, string> = {
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;",
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

  let out = "";
  const parts = String(text ?? "").split(/```/);
  for (let i = 0; i < parts.length; i++) {
    if (i % 2 === 1) {
      const code = escapeHtml(parts[i]).replace(/^[a-zA-Z0-9_-]+\n/, "").replace(/\n$/, "");
      out += '<pre class="code"><code>' + code + "</code></pre>";
      continue;
    }
    const lines = escapeHtml(parts[i]).split("\n");
    let list: string | null = null;
    const flushList = () => {
      if (list) {
        out += list + "</ul>";
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
        out += "<hr>";
      } else if (heading) {
        flushList();
        out += `<h${heading[1].length}>${inline(heading[2])}</h${heading[1].length}>`;
      } else if (bullet || ordered) {
        list = list ?? "<ul>";
        list += `<li>${inline((bullet ?? ordered)?.[1] ?? "")}</li>`;
      } else if (quote) {
        flushList();
        out += "<blockquote>" + inline(quote[1]) + "</blockquote>";
      } else if (raw.trim().length === 0) {
        flushList();
        out += "<br>";
      } else {
        flushList();
        out += inline(raw);
      }
    }
    flushList();
  }
  return <div className="bubble rich" dangerouslySetInnerHTML={{ __html: out }} />;
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

// ---------- rendering ----------

function CopyButton({ onCopy }: { onCopy: () => void }) {
  return (
    <span className="icon-actions">
      <button type="button" title="copy" onClick={onCopy}>
        ⧉ copy
      </button>
    </span>
  );
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

function Conversation({
  events,
  live,
  onCopy,
}: {
  events: EventFrame[];
  live: string | null;
  onCopy: (text: string) => void;
}) {
  const rows: ReactNode[] = [];
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
            <div className="bubble">{event.content ?? ""}</div>
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
              <div className="msg">
                <MarkdownText text={event.content} />
                <CopyButton onCopy={() => onCopy(event.content!)} />
              </div>
            </li>
          );
        }
        break;
      case "TOOL_RESULT":
        rows.push(
          <li className="group tool" key={"tr" + event.seq}>
            <div className="tool-block result">
              <span className={"dot " + (event.ok ? "ok" : "err")} />
              <span className="tool-name">{event.toolName}</span>
              <span className="result-text">{event.content ?? ""}</span>
            </div>
          </li>
        );
        break;
    }
  }
  if (live !== null) {
    rows.push(
      <li className="message streaming" key="live">
        <MarkdownText text={live || "…"} />
      </li>
    );
  }
  return <ol id="conversation">{rows}</ol>;
}

// ---------- app ----------

export default function App() {
  const { state, actions } = useChat();

  useEffect(() => {
    void actions.loadInitial();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const send = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    void actions.sendTask();
  };

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
        {(state.approvals.length > 0 || state.question) && (
          <div id="approval-rail">
            {state.approvals.map((approval) => (
              <div className="approval-card" key={approval.id}>
                <div className="approval-head">
                  <span className="dot pending" /> waiting for approval
                </div>
                <div className="approval-body">{approval.summary}</div>
                <div className="approval-actions">
                  <button type="button" onClick={() => void actions.decide(approval.id, false)}>
                    Reject
                  </button>
                  <button type="button" className="primary" onClick={() => void actions.decide(approval.id, true)}>
                    Allow once
                  </button>
                </div>
              </div>
            ))}
            {state.question && (
              <div className="approval-card question">
                <div className="approval-head">
                  <span className="dot pending" /> the agent asks
                </div>
                <div className="approval-body">{state.question.text}</div>
                <form
                  className="question-form"
                  onSubmit={(e) => {
                    e.preventDefault();
                    void actions.answerAsk();
                  }}
                >
                  <input
                    value={state.qInput}
                    onChange={(e) => actions.setQInput(e.target.value)}
                    placeholder="your answer…"
                    autoFocus
                  />
                  <button type="submit">Send</button>
                </form>
              </div>
            )}
          </div>
        )}
        <Conversation events={state.events} live={state.busy ? state.live : null} onCopy={(t) => void copyText(t)} />
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
