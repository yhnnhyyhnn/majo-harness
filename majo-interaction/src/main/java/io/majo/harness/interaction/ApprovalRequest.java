package io.majo.harness.interaction;

import java.util.UUID;

/**
 * A request to approve an operation (a tool call, a command) before it runs.
 * {@code summary} is the model-visible subject; {@code details} carries
 * context for the human deciding; {@code agent} is an optional originating
 * agent label (root turns leave it {@code null}, subagent scopes tag it).
 */
public record ApprovalRequest(String id, String summary, String details, String agent) {

    public static ApprovalRequest of(String summary, String details) {
        return new ApprovalRequest(UUID.randomUUID().toString(), summary, details, null);
    }

    public static ApprovalRequest of(String summary, String details, String agent) {
        return new ApprovalRequest(UUID.randomUUID().toString(), summary, details, agent);
    }
}
