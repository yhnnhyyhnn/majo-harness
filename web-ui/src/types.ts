// Typed mirror of the majo-web /api contract (single source of truth for the UI).

export type EventKind =
  | "TURN_START"
  | "TURN_END"
  | "USER_MESSAGE"
  | "ASSISTANT_MESSAGE"
  | "TOOL_RESULT"
  | "REQUEST_HEADER";

export interface ToolCallFrame {
  name: string;
  arguments?: string;
}

export interface EventFrame {
  seq: number;
  kind: EventKind;
  content?: string | null;
  toolCalls?: ToolCallFrame[];
  toolName?: string;
  ok?: boolean;
  model?: string;
  toolNames?: string[];
}

export interface SessionInfo {
  id: string;
  title?: string | null;
  eventCount: number;
}

export interface SessionDetail extends SessionInfo {
  events: EventFrame[];
}

export interface ModelState {
  model: string | null;
  models: string[];
}

export interface ApprovalFrame {
  id: string;
  summary: string;
  details?: string;
}

export interface QuestionFrame {
  id: string;
  text: string;
}

export type StreamEvent =
  | { event: "log"; data: EventFrame }
  | { event: "chunk"; data: { text: string } }
  | { event: "done"; data: { sessionId: string; answer: string } }
  | { event: "fail"; data: { message: string } }
  | { event: "approval"; data: ApprovalFrame }
  | { event: "question"; data: QuestionFrame };
