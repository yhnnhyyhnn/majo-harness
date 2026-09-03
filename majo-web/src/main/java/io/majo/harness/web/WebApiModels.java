package io.majo.harness.web;

import java.util.List;

/**
 * The /api wire contract — single source of truth for the browser: DTO records
 * that both WebMain serializes and {@code WebTypesGenerator} reflects into
 * {@code web-ui/src/types.ts}. Fields marked {@link OptionalWire} become
 * optional TS properties.
 */
public final class WebApiModels {

    private WebApiModels() {}

    /** One assistant tool call as logged (extra fields tolerated by the UI). */
    public record ToolCallFrame(String name, @OptionalWire String arguments,
            @OptionalWire String toolCallId) {}

    /** One durable session event frame relayed to the UI. */
    public record EventFrame(long seq, String kind, @OptionalWire String content,
            @OptionalWire List<ToolCallFrame> toolCalls, @OptionalWire String toolName,
            @OptionalWire Boolean ok, @OptionalWire String model,
            @OptionalWire List<String> toolNames) {}

    public record SessionInfo(String id, @OptionalWire String title, int eventCount) {}

    public record SessionsIndex(List<SessionInfo> sessions) {}

    public record SessionDetail(String id, @OptionalWire String title, List<EventFrame> events) {}

    public record CreateSession(String id) {}

    /** One-shot turn result (legacy JSON turn endpoint). */
    public record TurnResult(String sessionId, String answer, List<EventFrame> events) {}

    public record ModelState(@OptionalWire String model, List<String> models) {}

    public record Ok(boolean ok) {}

    /** Streamed text delta. */
    public record StreamChunk(String text) {}

    /** Stream completion. */
    public record StreamDone(String sessionId, String answer) {}

    /** Stream failure. */
    public record StreamFail(String message) {}

    /** Approval surfaced to the UI (a {@code pending.approval} frame). */
    public record ApprovalFrame(String id, String summary, @OptionalWire String details) {}

    /** Ask-user question surfaced to the UI (a {@code pending.question} frame). */
    public record QuestionFrame(String id, String text) {}

    /** Decision/answer POST results. */
    public record ApprovalDecision(@OptionalWire String decision) {}

    public record QuestionAnswer(@OptionalWire String answer) {}
}
