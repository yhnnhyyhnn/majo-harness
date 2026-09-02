package io.majo.harness.interaction;

import java.util.UUID;

/**
 * A request to approve an operation (a tool call, a command) before it runs.
 * {@code summary} is the model-visible subject; {@code details} carries
 * context for the human deciding.
 */
public record ApprovalRequest(String id, String summary, String details) {

    public static ApprovalRequest of(String summary, String details) {
        return new ApprovalRequest(UUID.randomUUID().toString(), summary, details);
    }
}
