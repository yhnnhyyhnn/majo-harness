import type {
  CreateSession,
  EventFrame,
  Info,
  ModelState,
  Ok,
  SessionDetail,
  SessionsIndex,
  SkillsIndex,
  StreamChunk,
  StreamDone,
  StreamFail,
  StreamEvent,
} from "./types";

// Typed HTTP + SSE client for the majo-web API ("connection" layer). Payload
// types are generated from the Java wire contract (see WebTypesGenerator).

async function rpc<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(path, options);
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new Error("bad response from server (HTTP " + response.status + ")");
  }
  if (!response.ok) {
    throw new Error(String((body as { error?: unknown })?.error || "HTTP " + response.status));
  }
  return body as T;
}

function postJson<T>(path: string, payload: unknown): Promise<T> {
  return rpc<T>(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
}

export const api = {
  sessions(): Promise<SessionsIndex> {
    return rpc("/api/sessions");
  },

  createSession(): Promise<CreateSession> {
    return postJson("/api/sessions", {});
  },

  session(id: string): Promise<SessionDetail> {
    return rpc("/api/sessions/" + encodeURIComponent(id));
  },

  modelState(): Promise<ModelState> {
    return rpc("/api/settings/model");
  },

  setModel(model: string): Promise<ModelState> {
    return postJson("/api/settings/model", { model });
  },

  decideApproval(id: string, granted: boolean): Promise<Ok> {
    return postJson("/api/approvals/" + encodeURIComponent(id), {
      decision: granted ? "allow" : "reject",
    });
  },

  answerQuestion(id: string, answer: string): Promise<Ok> {
    return postJson("/api/questions/" + encodeURIComponent(id), { answer });
  },

  skills(): Promise<SkillsIndex> {
    return rpc("/api/skills");
  },

  info(): Promise<Info> {
    return rpc("/api/info");
  },
};

export interface StreamHandlers {
  onEvent: (event: StreamEvent) => void;
  onOpen?: () => void;
  onError?: () => void;
}

function decode<T>(e: Event): T {
  return JSON.parse((e as MessageEvent).data) as T;
}

/**
 * Opens one SSE turn stream. Frame events are decoded into typed
 * {@link StreamEvent}s via {@code onEvent}.
 */
export function openTurnStream(sessionId: string, task: string, handlers: StreamHandlers): () => void {
  const url =
    "/api/turn/stream?sessionId=" + encodeURIComponent(sessionId) + "&task=" + encodeURIComponent(task);
  const source = new EventSource(url);
  source.addEventListener("log", (e) => handlers.onEvent({ event: "log", data: decode<EventFrame>(e) }));
  source.addEventListener("chunk", (e) => handlers.onEvent({ event: "chunk", data: decode<StreamChunk>(e) }));
  source.addEventListener("done", (e) => handlers.onEvent({ event: "done", data: decode<StreamDone>(e) }));
  source.addEventListener("fail", (e) => handlers.onEvent({ event: "fail", data: decode<StreamFail>(e) }));
  source.addEventListener("approval", (e) => {
    handlers.onEvent({ event: "approval", data: decode<import("./types").ApprovalFrame>(e) });
  });
  source.addEventListener("question", (e) => {
    handlers.onEvent({ event: "question", data: decode<import("./types").QuestionFrame>(e) });
  });
  source.onopen = () => handlers.onOpen?.();
  source.onerror = () => handlers.onError?.();
  return () => source.close();
}
