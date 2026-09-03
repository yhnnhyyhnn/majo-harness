import type { EventFrame, ModelState, SessionDetail, SessionInfo, StreamEvent } from "./types";

// Typed HTTP + SSE client for the majo-web API ("connection" layer).

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
  sessions(): Promise<{ sessions: SessionInfo[] }> {
    return rpc("/api/sessions");
  },

  createSession(): Promise<{ id: string }> {
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

  decideApproval(id: string, granted: boolean): Promise<{ ok: boolean }> {
    return postJson("/api/approvals/" + encodeURIComponent(id), {
      decision: granted ? "allow" : "reject",
    });
  },

  answerQuestion(id: string, answer: string): Promise<{ ok: boolean }> {
    return postJson("/api/questions/" + encodeURIComponent(id), { answer });
  },
};

export interface StreamHandlers {
  onEvent: (event: StreamEvent) => void;
  onOpen?: () => void;
  onError?: () => void;
}

/**
 * Opens one SSE turn stream. Returns a close() handle. Frame events are
 * decoded into typed {@link StreamEvent}s via {@code onEvent}.
 */
export function openTurnStream(sessionId: string, task: string, handlers: StreamHandlers): () => void {
  const url =
    "/api/turn/stream?sessionId=" + encodeURIComponent(sessionId) + "&task=" + encodeURIComponent(task);
  const source = new EventSource(url);
  source.addEventListener("log", (e) =>
    handlers.onEvent({ event: "log", data: JSON.parse((e as MessageEvent).data) as EventFrame })
  );
  source.addEventListener("chunk", (e) =>
    handlers.onEvent({
      event: "chunk",
      data: JSON.parse((e as MessageEvent).data) as { text: string },
    })
  );
  source.addEventListener("done", (e) =>
    handlers.onEvent({
      event: "done",
      data: JSON.parse((e as MessageEvent).data) as { sessionId: string; answer: string },
    })
  );
  source.addEventListener("fail", (e) =>
    handlers.onEvent({
      event: "fail",
      data: JSON.parse((e as MessageEvent).data) as { message: string },
    })
  );
  source.addEventListener("approval", (e) =>
    handlers.onEvent({
      event: "approval",
      data: JSON.parse((e as MessageEvent).data) as import("./types").ApprovalFrame,
    })
  );
  source.addEventListener("question", (e) =>
    handlers.onEvent({
      event: "question",
      data: JSON.parse((e as MessageEvent).data) as import("./types").QuestionFrame,
    })
  );
  source.onopen = () => handlers.onOpen?.();
  source.onerror = () => handlers.onError?.();
  return () => source.close();
}
