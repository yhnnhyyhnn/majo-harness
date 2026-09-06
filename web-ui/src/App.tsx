import { useEffect, useRef, useState, type ReactNode } from "react";
import { api } from "./api";
import { SlotRoot, useSlots, type CommandSeat, type RailProps } from "./slots";
import { FEATURES } from "./features";
import type { EventFrame, EventKind, SearchHit, SessionInfo } from "./types";
import { useChat } from "./useChat";

// li wrapper styles are a shell concern; inner content comes from slots.
const kindStyle: Partial<Record<EventKind, string>> = {
  USER_MESSAGE: "message user",
  ASSISTANT_MESSAGE: "message",
  REQUEST_HEADER: "group meta",
  TOOL_RESULT: "group tool",
};

const escapeRegex = (text: string): string =>
  text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** Renders text with case-insensitive matches of {@code query} wrapped in <mark>. */
function Marked({ text, query }: { text: string; query: string }) {
  const needle = query.trim();
  if (!needle) return <>{text}</>;
  const parts = text.split(new RegExp("(" + escapeRegex(needle) + ")", "ig"));
  return (
    <>
      {parts.map((part, index) =>
        part.toLowerCase() === needle.toLowerCase() ? (
          <mark key={index}>{part}</mark>
        ) : (
          <span key={index}>{part}</span>
        )
      )}
    </>
  );
}

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
  const { rails, sidebarSections, commands } = useSlots();
  const frameRef = useRef<HTMLIFrameElement | null>(null);
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<SearchHit[] | null>(null);
  const [selected, setSelected] = useState(0);
  const [cmdSelected, setCmdSelected] = useState(0);
  const [managing, setManaging] = useState(false);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [showArchived, setShowArchived] = useState(false);
  const [archivedList, setArchivedList] = useState<SessionInfo[]>([]);
  const [archTick, setArchTick] = useState(0);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const importRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    void actions.loadInitial();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // archived view: fetch the archived list whenever toggled or after actions
  useEffect(() => {
    if (!showArchived) return;
    void api
      .sessions("archived")
      .then((index) => setArchivedList([...index.sessions].reverse()))
      .catch(() => setArchivedList([]));
  }, [showArchived, archTick]);

  // idle catch-up: while a session is open and not busy, poll events newer
  // than our cursor (covers other tabs / child runs finishing off-stream)
  // session search: debounced full-text query against durable events
  useEffect(() => {
    const needle = query.trim();
    if (needle.length < 2) {
      setHits(null);
      return;
    }
    const timer = window.setTimeout(() => {
      void api
        .search(needle)
        .then((index) => {
          setHits(index.hits || []);
          setSelected(0);
        })
        .catch(() => setHits([]));
    }, 250);
    return () => window.clearTimeout(timer);
  }, [query]);

  const openSearchHit = (hit: SearchHit) => {
    void actions.selectSession(hit.id, typeof hit.seq === "number" ? hit.seq : undefined);
    setQuery("");
    setHits(null);
  };

  const completeCommand = (name: string) => {
    actions.setInput("/" + name + " ");
    const input = document.getElementById("input");
    input?.focus();
  };

  const exactCommand = (raw: string): boolean => {
    const typed = raw.trim().slice(1).toLowerCase();
    return commands.some((candidate) =>
      candidate.names.some((name) => name.toLowerCase() === typed)
    );
  };

  const onSearchKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    const list = hits ?? [];
    if (event.key === "ArrowDown") {
      event.preventDefault();
      if (list.length) setSelected((selected + 1) % list.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      if (list.length) setSelected((selected - 1 + list.length) % list.length);
    } else if (event.key === "Enter") {
      event.preventDefault();
      const target = list[selected] ?? list[0];
      if (target) openSearchHit(target);
    } else if (event.key === "Escape") {
      event.preventDefault();
      setQuery("");
      setHits(null);
      (event.target as HTMLInputElement).blur();
    }
  };

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

  // slash-command completions: typing a leading "/" floats matching commands
  const commandHints = (() => {
    if (state.busy || !state.input.trim().startsWith("/")) return [];
    const typed = state.input.trim().slice(1).toLowerCase();
    const seen = new Set<string>();
    const all = commands
      .flatMap((candidate) =>
        candidate.names.map((name) => ({ command: candidate, name }))
      )
      .filter(({ name }) => !seen.has(name) && seen.add(name))
      .sort((a, b) => {
        const ga = a.command.group || "";
        const gb = b.command.group || "";
        return ga === gb ? a.name.localeCompare(b.name) : ga.localeCompare(gb);
      });
    return typed ? all.filter(({ name }) => name.startsWith(typed)) : all;
  })();

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

  const setArchived = async (id: string, archived: boolean) => {
    try {
      await api.archiveSession(id, archived);
      actions.retry();
      setArchTick((tick) => tick + 1);
      flash(archived ? "archived" : "restored");
    } catch (error) {
      flash("archive failed: " + String(error));
    }
  };

  const sessionsShown = showArchived ? archivedList : state.sessions;
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
        <div id="session-search">
          <input
            type="search"
            placeholder="Search sessions…"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setSelected(0);
            }}
            onKeyDown={onSearchKeyDown}
          />
          {query.trim().length >= 2 && (
            <div id="search-hits">
              {hits === null && <div className="meta">searching…</div>}
              {hits && hits.length === 0 && <div className="meta">no matches</div>}
              {hits?.map((hit, index) => (
                <div
                  key={hit.id}
                  className={
                    "search-hit" + (index === selected ? " active" : "")
                  }
                  onMouseEnter={() => setSelected(index)}
                >
                  <button type="button" className="hit-open" onClick={() => openSearchHit(hit)}>
                    <span className="title">
                      <Marked text={hit.title || "Untitled"} query={query} />
                      {hit.archived && <span className="arch-tag">archived</span>}
                    </span>
                    <span className="meta snippet">
                      <Marked text={hit.snippet ?? ""} query={query} />
                    </span>
                  </button>
                  {hit.archived && (
                    <button
                      type="button"
                      className="hit-restore"
                      title="restore then open"
                      onClick={() => {
                        void api
                          .archiveSession(hit.id, false)
                          .then(() => {
                            setArchTick((tick) => tick + 1);
                            openSearchHit(hit);
                          })
                          .catch((error: unknown) => flash("restore failed: " + String(error)));
                      }}
                    >
                      ↩
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
        <div id="session-tools">
          <button
            type="button"
            className="side-refresh"
            onClick={() => importRef.current?.click()}
          >
            Import JSONL…
          </button>
          <input
            ref={importRef}
            type="file"
            accept=".jsonl,application/x-ndjson"
            hidden
            onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = "";
              if (!file) return;
              void file
                .text()
                .then((text) => api.importSession(text))
                .then((created) => {
                  flash("imported session " + created.id.slice(0, 8));
                  return actions.selectSession(created.id);
                })
                .catch((error: unknown) => flash("import failed: " + String(error)));
            }}
          />
          <div id="view-toggle">
            <button
              type="button"
              className={"side-refresh" + (!showArchived ? " on" : "")}
              onClick={() => {
                setManaging(false);
                setShowArchived(false);
              }}
            >
              <span className="mode-ico">{!showArchived ? "●" : "○"}</span>
              Active
            </button>
            <button
              type="button"
              className={"side-refresh" + (showArchived ? " on" : "")}
              onClick={() => {
                setManaging(false);
                setShowArchived(true);
              }}
            >
              <span className="mode-ico">{showArchived ? "●" : "○"}</span>
              Archived
            </button>
          </div>
          {!managing && sessionsShown.length > 0 && (
            <button type="button" className="side-refresh" onClick={() => setManaging(true)}>
              Manage sessions
            </button>
          )}
          {managing && (
            <div id="session-manage">
              <button
                type="button"
                className="side-refresh"
                onClick={() =>
                  setPicked(
                    picked.size === sessionsShown.length
                      ? new Set()
                      : new Set(sessionsShown.map((session) => session.id))
                  )
                }
              >
                {picked.size === state.sessions.length ? "Clear" : "Select all"}
              </button>
              <button
                type="button"
                className="side-refresh"
                disabled={picked.size === 0}
                onClick={async () => {
                  const ids = [...picked];
                  for (const id of ids) {
                    await api.archiveSession(id, true).catch(() => {});
                  }
                  flash("archived " + ids.length + " session" + (ids.length === 1 ? "" : "s"));
                  actions.retry();
                  setArchTick((tick) => tick + 1);
                  setPicked(new Set());
                  setManaging(false);
                }}
              >
                Archive ({picked.size})
              </button>
              <button
                type="button"
                className="side-refresh danger"
                disabled={picked.size === 0}
                onClick={() => {
                  if (
                    window.confirm(
                      "Delete " + picked.size + " session" + (picked.size === 1 ? "" : "s") + "?"
                    )
                  ) {
                    void actions.deleteSessions([...picked]).then(() => {
                      setPicked(new Set());
                      setManaging(false);
                    });
                  }
                }}
              >
                Delete ({picked.size})
              </button>
              <button
                type="button"
                className="side-refresh"
                onClick={() => {
                  setPicked(new Set());
                  setManaging(false);
                }}
              >
                Done
              </button>
            </div>
          )}
        </div>
        <nav id="session-list">
          {sessionsShown.length === 0 && (
            <div className="meta">{showArchived ? "nothing archived" : "no sessions yet"}</div>
          )}
          {sessionsShown.map((s) => (
            <div
              key={s.id}
              className={"session-row" + (s.id === state.sessionId ? " active" : "")}
            >
              {managing && (
                <input
                  type="checkbox"
                  className="pick"
                  checked={picked.has(s.id)}
                  onChange={() =>
                    setPicked((previous) => {
                      const next = new Set(previous);
                      if (next.has(s.id)) next.delete(s.id);
                      else next.add(s.id);
                      return next;
                    })
                  }
                />
              )}
              <button
                type="button"
                className="session"
                onClick={() => void actions.selectSession(s.id)}
              >
                <span className="title">{s.title || "Untitled " + s.id.slice(0, 8)}</span>
                <span className="meta">{s.eventCount} events</span>
              </button>
              {!managing && (
                <span className="session-ops">
                  <button
                    type="button"
                    title="export JSONL"
                    onClick={(event) => {
                      event.stopPropagation();
                      const anchor = document.createElement("a");
                      anchor.href =
                        "/api/sessions/" + encodeURIComponent(s.id) + "/export";
                      anchor.download = "";
                      document.body.appendChild(anchor);
                      anchor.click();
                      anchor.remove();
                    }}
                  >
                    ⬇
                  </button>
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
                    title={showArchived ? "restore" : "archive"}
                    disabled={state.busy}
                    onClick={(event) => {
                      event.stopPropagation();
                      void setArchived(s.id, !showArchived);
                    }}
                  >
                    {showArchived ? "↩" : "📁"}
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
              )}
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
            {commandHints.length > 0 && (
              <div id="command-hints">
                {commandHints.map(({ command, name }, index) => {
                  const previous = commandHints[index - 1];
                  const showGroup =
                    !previous || (previous.command.group || "") !== (command.group || "");
                  return (
                    <span key={name}>
                      {showGroup && (
                        <div className="hint-group">{command.group || "commands"}</div>
                      )}
                      <button
                        type="button"
                        className={index === cmdSelected ? "active" : undefined}
                        onMouseEnter={() => setCmdSelected(index)}
                        onClick={() => completeCommand(name)}
                      >
                        <code>/{name}</code>
                        {command.usage && <span className="hint-usage">{command.usage}</span>}
                        <span className="meta hint-desc">{command.description}</span>
                      </button>
                    </span>
                  );
                })}
              </div>
            )}
            <form id="composer" onSubmit={send}>
          <textarea
            id="input"
            rows={1}
            value={state.input}
            placeholder="Type a task or /command… (Enter sends, Tab completes)"
            onChange={(e) => {
              actions.setInput(e.target.value);
              setCmdSelected(0);
            }}
            onKeyDown={(e) => {
              const raw = state.input.trim();
              if (raw.startsWith("/")) {
                const hints = commandHints;
                if (e.key === "Escape") {
                  e.preventDefault();
                  actions.setInput("");
                  return;
                }
                if (e.key === "ArrowDown") {
                  e.preventDefault();
                  if (hints.length) setCmdSelected((cmdSelected + 1) % hints.length);
                  return;
                }
                if (e.key === "ArrowUp") {
                  e.preventDefault();
                  if (hints.length)
                    setCmdSelected((cmdSelected - 1 + hints.length) % hints.length);
                  return;
                }
                if (e.key === "Tab") {
                  e.preventDefault();
                  const pick = hints[Math.min(cmdSelected, hints.length - 1)];
                  if (pick) completeCommand(pick.name);
                  return;
                }
                if (e.key === "Enter" && !e.shiftKey && hints.length && !exactCommand(raw)) {
                  e.preventDefault();
                  const pick = hints[cmdSelected % hints.length];
                  if (pick) completeCommand(pick.name);
                  return;
                }
              }
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
