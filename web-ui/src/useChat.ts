import { useRef } from "react";
import { api, openTurnStream } from "./api";
import { createStore, useSnapshot, type Store } from "./store";
import type { ApprovalFrame, EventFrame, QuestionFrame, SessionInfo } from "./types";

export interface ChatState {
  sessions: SessionInfo[];
  sessionId: string | null;
  events: EventFrame[];
  /** Highest durable seq already in {@code events} (poll cursor). */
  cursor: number;
  /** Durable seq → "up" | "down" for the open session. */
  feedback: Record<number, "up" | "down">;
  title: string;
  /** Per-session model override (null = follow the global model). */
  sessionModel: string | null;
  model: string | null;
  models: string[];
  /** Transient banner text (slash-command feedback). */
  notice: string | null;
  /** Opened plugin frontend (name + hosted url); null = conversation view. */
  pluginView: { name: string; url: string } | null;
  input: string;
  busy: boolean;
  offline: boolean;
  live: string | null;
  approvals: ApprovalFrame[];
  question: QuestionFrame | null;
  qInput: string;
}

export interface ChatActions {
  changeModel(model: string): Promise<void>;
  selectSession(id: string): Promise<void>;
  newChat(): void;
  renameSession(id: string, title: string): Promise<void>;
  deleteSession(id: string): Promise<void>;
  changeSessionModel(model: string | null): Promise<void>;
  rate(seq: number, value: "up" | "down" | null): Promise<void>;
  openPlugin(name: string, url: string): void;
  closePlugin(): void;
  setNotice(message: string | null): void;
  setInput(value: string): void;
  setQInput(value: string): void;
  sendTask(): Promise<void>;
  decide(id: string, granted: boolean): Promise<void>;
  answerAsk(): Promise<void>;
  syncEvents(): Promise<void>;
  retry(): void;
  loadInitial(): Promise<void>;
}

const initial = (): ChatState => ({
  sessions: [],
  sessionId: null,
  events: [],
  cursor: 0,
  feedback: {},
  title: "New chat",
  sessionModel: null,
  model: null,
  models: [],
  notice: null,
  pluginView: null,
  input: "",
  busy: false,
  offline: false,
  live: null,
  approvals: [],
  question: null,
  qInput: "",
});

export function createChat(store: Store<ChatState>): ChatActions {
  let streamClose: (() => void) | null = null;
  let noticeTimer: number | undefined;

  const closeStream = () => {
    streamClose?.();
    streamClose = null;
  };

  const loadSessions = async (): Promise<SessionInfo[]> => {
    const index = await api.sessions();
    store.set({ sessions: [...index.sessions].reverse(), offline: false });
    return index.sessions;
  };

  const feedbackOf = async (sessionId: string): Promise<Record<number, "up" | "down">> => {
    try {
      const index = await api.feedback(sessionId);
      const record: Record<number, "up" | "down"> = {};
      for (const entry of index.entries) {
        if (entry.value === "up" || entry.value === "down") record[entry.seq] = entry.value;
      }
      return record;
    } catch {
      return {};
    }
  };

  const push = (event: EventFrame) =>
    store.set((state) => {
      const seq = typeof event.seq === "number" && event.seq > 0 ? event.seq : 0;
      return {
        events: [...state.events, event],
        cursor: seq > state.cursor ? seq : state.cursor,
      };
    });

  const sendTask = async () => {
    const task = store.get().input.trim();
    if (!task || store.get().busy) return;
    store.set({ busy: true, live: "", approvals: [], question: null });
    try {
      let id = store.get().sessionId;
      if (!id) {
        const created = await api.createSession();
        id = created.id;
        store.set({ sessionId: id });
      }
      push({ seq: -Date.now(), kind: "USER_MESSAGE", content: task });
      streamClose = openTurnStream(id, task, {
        onEvent(event) {
          switch (event.event) {
            case "log":
              push(event.data);
              break;
            case "chunk":
              store.set((state) => ({ live: (state.live || "") + event.data.text }));
              break;
            case "approval":
              store.set((state) => ({ approvals: [...state.approvals, event.data] }));
              break;
            case "question":
              store.set({ question: event.data });
              break;
            case "done":
              store.set({ live: null, approvals: [], question: null });
              // durable log is the source of truth: re-fetch the session so
              // the transcript, cursor and feedback align on real seqs
              void api
                .session(id)
                .then(async (detail) => {
                  const cursor = detail.events.reduce(
                    (max, e) => (typeof e.seq === "number" && e.seq > 0 ? Math.max(max, e.seq) : max),
                    0
                  );
                  store.set({
                    events: detail.events,
                    cursor,
                    title: detail.title || "New chat",
                    sessionModel: detail.sessionModel ?? null,
                    feedback: await feedbackOf(id),
                  });
                  void loadSessions().catch(() => {});
                })
                .catch(() => {
                  push({ seq: 1e12, kind: "ASSISTANT_MESSAGE", content: event.data.answer });
                });
              closeStream();
              store.set({ busy: false, input: "" });
              break;
            case "fail":
              store.set({ live: null, approvals: [], question: null });
              push({ seq: 1e12 + 1, kind: "ASSISTANT_MESSAGE", content: "error: " + event.data.message });
              closeStream();
              store.set({ busy: false });
              break;
          }
        },
        onError() {
          closeStream();
          store.set({ busy: false });
        },
      });
    } catch (error) {
      store.set({ live: null });
      push({ seq: 1e12 + 2, kind: "ASSISTANT_MESSAGE", content: "error: " + String(error) });
      store.set({ busy: false });
    }
  };

  return {
    async changeModel(model) {
      const state = await api.setModel(model);
      store.set({ model: state.model });
    },
    async changeSessionModel(model) {
      const id = store.get().sessionId;
      if (!id || store.get().busy) return;
      try {
        if (model) {
          await api.setSessionModel(id, model);
        } else {
          await api.clearSessionModel(id);
        }
        store.set({ sessionModel: model });
      } catch (error) {
        console.error("session model change failed", error);
      }
    },
    async selectSession(id) {
      closeStream();
      const detail = await api.session(id);
      const cursor = detail.events.reduce(
        (max, event) => (typeof event.seq === "number" && event.seq > 0 ? Math.max(max, event.seq) : max),
        0
      );
      store.set({
        sessionId: id,
        title: detail.title || "New chat",
        sessionModel: detail.sessionModel ?? null,
        events: detail.events,
        cursor,
        feedback: await feedbackOf(id),
        approvals: [],
        question: null,
      });
      void loadSessions();
    },
    newChat() {
      closeStream();
      store.set({
        sessionId: null,
        events: [],
        title: "New chat",
        live: null,
        approvals: [],
        question: null,
      });
      void loadSessions().catch(() => {});
    },
    async renameSession(id, title) {
      const normalized = (title || "").trim();
      if (!normalized || store.get().busy) return;
      try {
        await api.renameSession(id, normalized);
        if (store.get().sessionId === id) {
          store.set({ title: normalized });
        }
      } catch (error) {
        console.error("rename failed", error);
      }
      void loadSessions().catch(() => {});
    },
    async deleteSession(id) {
      if (store.get().busy) return;
      const wasActive = store.get().sessionId === id;
      try {
        await api.deleteSession(id);
      } catch (error) {
        console.error("delete failed", error);
        return;
      }
      closeStream();
      const list = await loadSessions();
      const fallback = list.length > 0 ? list[list.length - 1] : null;
      if (wasActive && fallback) {
        const detail = await api.session(fallback.id);
        store.set({
          sessionId: fallback.id,
          title: detail.title || "New chat",
          events: detail.events,
          live: null,
          approvals: [],
          question: null,
        });
      } else if (wasActive) {
        store.set({ sessionId: null, events: [], title: "New chat", cursor: 0 });
      }
    },
    setInput(value) {
      store.set({ input: value });
    },
    setQInput(value) {
      store.set({ qInput: value });
    },
    sendTask,
    async rate(seq, value) {
      const id = store.get().sessionId;
      if (!id || store.get().busy || typeof seq !== "number" || seq <= 0) return;
      const previous = store.get().feedback[seq];
      store.set((state) => {
        const feedback = { ...state.feedback };
        if (value) {
          feedback[seq] = value;
        } else {
          delete feedback[seq];
        }
        return { feedback };
      });
      try {
        if (value) {
          await api.rate(id, seq, value);
        } else {
          await api.clearRate(id, seq);
        }
      } catch (error) {
        console.error("rating failed", error);
        store.set((state) => {
          const feedback = { ...state.feedback };
          if (previous) {
            feedback[seq] = previous;
          } else {
            delete feedback[seq];
          }
          return { feedback };
        });
      }
    },
    async decide(id, granted) {
      try {
        await api.decideApproval(id, granted);
      } catch (error) {
        console.error("approval decision failed", error);
      }
      store.set((state) => ({ approvals: state.approvals.filter((a) => a.id !== id) }));
    },
    async answerAsk() {
      const question = store.get().question;
      const answer = store.get().qInput;
      if (!question) return;
      try {
        await api.answerQuestion(question.id, answer);
      } catch (error) {
        console.error("answer failed", error);
      }
      store.set({ question: null, qInput: "" });
    },
    async syncEvents() {
      const current = store.get();
      if (!current.sessionId || current.busy) return;
      try {
        const delta = await api.eventsSince(current.sessionId, current.cursor);
        for (const event of delta.events) push(event);
        if (delta.lastSeq > current.cursor) {
          store.set({ cursor: delta.lastSeq });
        }
      } catch {
        // transient: next poll retries; conversation stays usable offline
      }
    },
    openPlugin(name, url) {
      closeStream();
      store.set({ pluginView: { name, url } });
    },
    closePlugin() {
      store.set({ pluginView: null });
    },
    setNotice(message) {
      if (noticeTimer !== undefined) {
        window.clearTimeout(noticeTimer);
      }
      store.set({ notice: message });
      if (message) {
        noticeTimer = window.setTimeout(() => {
          store.set({ notice: null });
          noticeTimer = undefined;
        }, 8000);
      }
    },
    retry() {
      store.set({ offline: false });
      void loadSessions().catch(() => {});
    },
    async loadInitial() {
      try {
        const modelState = await api.modelState();
        store.set({ model: modelState.model, models: modelState.models });
      } catch {
        // settings endpoint absent on older profiles: keep defaults
      }
      try {
        const all = await loadSessions();
        let id = store.get().sessionId;
        if (!id && all.length) id = all[all.length - 1].id;
        if (id) {
          const detail = await api.session(id);
          store.set({
            sessionId: id,
            title: detail.title || "New chat",
            sessionModel: detail.sessionModel ?? null,
            events: detail.events,
            feedback: await feedbackOf(id),
          });
        }
      } catch {
        store.set({ offline: true });
      }
    },
  };
}

export function useChat(): { state: ChatState; actions: ChatActions } {
  const storeRef = useRef<Store<ChatState> | null>(null);
  const actionsRef = useRef<ChatActions | null>(null);
  if (!storeRef.current) {
    storeRef.current = createStore<ChatState>(initial());
    actionsRef.current = createChat(storeRef.current);
  }
  const state = useSnapshot(storeRef.current);
  return { state, actions: actionsRef.current! };
}
