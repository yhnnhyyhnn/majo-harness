import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import type { SearchHit, SessionInfo } from "../types";
import type { useChat } from "../useChat";

type ChatState = ReturnType<typeof useChat>["state"];
type ChatActions = ReturnType<typeof useChat>["actions"];

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

/**
 * The left nav's session area: full-text search with keyboard navigation,
 * JSONL import, Active/Archived view toggle, manage mode (multi-select batch
 * archive/delete) and the session list with per-row ops. Search and the list
 * live together because a search-hit restore must refresh the archived view.
 */
export function SessionSidebar({
  state,
  actions,
}: {
  state: ChatState;
  actions: ChatActions;
}) {
  const flash = (message: string) => actions.setNotice(message);
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<SearchHit[] | null>(null);
  const [selected, setSelected] = useState(0);
  const [managing, setManaging] = useState(false);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [showArchived, setShowArchived] = useState(false);
  const [archivedList, setArchivedList] = useState<SessionInfo[]>([]);
  const [archTick, setArchTick] = useState(0);
  const importRef = useRef<HTMLInputElement | null>(null);

  // archived view: fetch the archived list whenever toggled or after actions
  useEffect(() => {
    if (!showArchived) return;
    void api
      .sessions("archived")
      .then((index) => setArchivedList([...index.sessions].reverse()))
      .catch(() => setArchivedList([]));
  }, [showArchived, archTick]);

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
                className={"search-hit" + (index === selected ? " active" : "")}
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
    </>
  );
}
