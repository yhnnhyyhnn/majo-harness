// AUTO-GENERATED from io.majo.harness.web.WebApiModels and io.majo.harness.session.SessionEventType
// Do not edit by hand — run scripts/gen-web-types.sh after changing the wire contract.

export type EventKind =
  | "TURN_START"
  | "USER_MESSAGE"
  | "ASSISTANT_MESSAGE"
  | "TOOL_RESULT"
  | "TURN_END"
  | "REQUEST_HEADER"

export interface ApprovalDecision {
  decision?: string;
}

export interface ApprovalFrame {
  id: string;
  summary: string;
  details?: string;
}

export interface CreateSession {
  id: string;
}

export interface EventFrame {
  seq: number;
  kind: EventKind;
  content?: string;
  toolCalls?: ToolCallFrame[];
  toolName?: string;
  ok?: boolean;
  model?: string;
  toolNames?: string[];
  data?: Record<string, unknown>;
}

export interface EventsDelta {
  since: number;
  lastSeq: number;
  events: EventFrame[];
}

export interface Info {
  version: string;
  models: string[];
  tools: string[];
  skills: number;
}

export interface ModelState {
  model?: string;
  models: string[];
}

export interface Ok {
  ok: boolean;
}

export interface QuestionAnswer {
  answer?: string;
}

export interface QuestionFrame {
  id: string;
  text: string;
}

export interface SessionDetail {
  id: string;
  title?: string;
  events: EventFrame[];
}

export interface SessionInfo {
  id: string;
  title?: string;
  eventCount: number;
}

export interface SessionsIndex {
  sessions: SessionInfo[];
}

export interface SkillInfo {
  name: string;
  description?: string;
}

export interface SkillsIndex {
  skills: SkillInfo[];
}

export interface StreamChunk {
  text: string;
}

export interface StreamDone {
  sessionId: string;
  answer: string;
}

export interface StreamFail {
  message: string;
}

export interface SubagentRun {
  task: string;
  status: string;
  detail?: string;
  atMillis: number;
}

export interface SubagentsIndex {
  runs: SubagentRun[];
}

export interface ToolCallFrame {
  name: string;
  arguments?: string;
  toolCallId?: string;
}

export interface TurnResult {
  sessionId: string;
  answer: string;
  events: EventFrame[];
}

export type StreamEvent =
  | { event: "log"; data: EventFrame }
  | { event: "chunk"; data: StreamChunk }
  | { event: "done"; data: StreamDone }
  | { event: "fail"; data: StreamFail }
  | { event: "approval"; data: ApprovalFrame }
  | { event: "question"; data: QuestionFrame };
