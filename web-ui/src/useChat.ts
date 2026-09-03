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
  title: string;
  model: string | null;
  models: string[];
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
  title: "New chat",
  model: null,
  models: [],
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

  const closeStream = () => {
    streamClose?.();
    streamClose = null;
  };

  const loadSessions = async (): Promise<SessionInfo[]> => {
    const index = await api.sessions();
    store.set({ sessions: [...index.sessions].reverse(), offline: false });
    return index.sessions;
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
              push({ seq: 1e12, kind: "ASSISTANT_MESSAGE", content: event.data.answer });
              void loadSessions().then((all) => {
                const mine = all.find((s) => s.id === event.data.sessionId);
                if (mine) store.set({ title: mine.title || "New chat" });
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
        events: detail.events,
        cursor,
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
            events: detail.events,
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
