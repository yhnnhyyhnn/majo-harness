import { useEffect, useState } from "react";
import { api } from "../api";
import type { TodoItem } from "../types";
import type { useChat } from "../useChat";

type ChatState = ReturnType<typeof useChat>["state"];

const STATUS_GLYPH: Record<string, string> = {
  pending: "☐",
  in_progress: "↻",
  completed: "✅",
};

/**
 * The session todo list (dsh ui-conversation's TodoPanel): the model
 * maintains it via todo_write; the panel re-reads when the session changes
 * and whenever a turn finishes (busy flips back to idle).
 */
export function TodoPanel({ state }: { state: ChatState }) {
  const [items, setItems] = useState<TodoItem[]>([]);
  const sessionId = state.sessionId;
  const busy = state.busy;

  useEffect(() => {
    if (!sessionId) {
      setItems([]);
      return;
    }
    let cancelled = false;
    void api
      .todos(sessionId)
      .then((index) => {
        if (!cancelled) setItems(index.items || []);
      })
      .catch(() => {
        if (!cancelled) setItems([]);
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId, busy]);

  if (!sessionId || items.length === 0) return null;
  const done = items.filter((item) => item.status === "completed").length;
  return (
    <div id="todo-panel">
      <div className="meta todo-head">
        todos {done}/{items.length}
      </div>
      <ul className="todo-list">
        {items.map((item, index) => (
          <li key={index} className={"todo-" + (item.status || "pending")}>
            <span className="todo-glyph">{STATUS_GLYPH[item.status] ?? "☐"}</span>{" "}
            {item.content}
          </li>
        ))}
      </ul>
    </div>
  );
}
