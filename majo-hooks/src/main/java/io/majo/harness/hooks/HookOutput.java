package io.majo.harness.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The outcome of one hook run, decoded from its exit code + stdout + stderr
 * (dsh {@code parseHookOutput} parity). The two blocking channels stay
 * distinct as upstream keeps them: exit code 2 blocks with the stderr as the
 * reason; a structured stdout ({@code continue:false} or a
 * {@code hookSpecificOutput.permissionDecision}) can block, allow, or ask.
 * Plain (non-JSON) stdout on a clean exit is surfaced as
 * {@code additionalContext} (the Codex SessionStart/UserPromptSubmit shape —
 * useful context without any JSON ceremony). Fields the hook did not exercise
 * stay null; a hook that could not run blocks nothing.
 */
public final class HookOutput {

    /** The neutral decision a hook expressed (from {@code permissionDecision}). */
    public enum Decision { ALLOW, DENY, ASK }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final Integer exitCode;
    public final String stdout;
    public final String stderr;
    public final Boolean proceed;       // continue:false → false (halt request)
    public final String stopReason;
    public final Decision decision;
    public final String reason;
    public final String additionalContext;
    public final String systemMessage;

    public Integer exitCode() { return exitCode; }
    public String stdout() { return stdout; }
    public String stderr() { return stderr; }
    public Boolean proceed() { return proceed; }
    public String stopReason() { return stopReason; }
    public Decision decision() { return decision; }
    public String reason() { return reason; }
    public String additionalContext() { return additionalContext; }
    public String systemMessage() { return systemMessage; }

    private HookOutput(Integer exitCode, String stdout, String stderr, Boolean proceed,
            String stopReason, Decision decision, String reason,
            String additionalContext, String systemMessage) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.proceed = proceed;
        this.stopReason = stopReason;
        this.decision = decision;
        this.reason = reason;
        this.additionalContext = additionalContext;
        this.systemMessage = systemMessage;
    }

    /** Whether this outcome blocks the intercepted action (exit 2 or deny/block). */
    public boolean blocks() {
        if (exitCode != null && exitCode == 2) {
            return true;
        }
        if (proceed != null && !proceed) {
            return true;
        }
        return decision == Decision.DENY;
    }

    /** The model-visible block reason (stderr for exit-2, the stated reason otherwise). */
    public String blockReason() {
        if (exitCode != null && exitCode == 2) {
            return stderr == null || stderr.isBlank() ? "blocked by hook" : stderr.strip();
        }
        if (stopReason != null && !stopReason.isBlank()) {
            return stopReason.strip();
        }
        return reason == null || reason.isBlank() ? "blocked by hook" : reason.strip();
    }

    /**
     * Whether the hook explicitly approved the action ({@code allow}/
     * {@code approve}) — an advisory affirmative; the approval seam stays the
     * authority (dsh bridges likewise treat allow as a decision input, not a
     * bypass).
     */
    public boolean allows() {
        return decision == Decision.ALLOW;
    }

    /** Decodes a run's outcome; never throws — a malformed stdout degrades to plain-text context. */
    public static HookOutput of(Integer exitCode, String stdout, String stderr) {
        String cleanStdout = stdout == null ? "" : stdout.strip();
        String cleanStderr = stderr == null ? "" : stderr.strip();
        Boolean proceed = null;
        String stopReason = null;
        Decision decision = null;
        String reason = null;
        String additionalContext = null;
        String systemMessage = null;
        if (!cleanStdout.isEmpty()) {
            JsonNode root = null;
            try {
                root = MAPPER.readTree(cleanStdout);
            } catch (Exception ignored) {
                // plain text: rendered as additional context on a clean exit
            }
            if (root != null && root.isObject()) {
                if (root.hasNonNull("continue") && root.get("continue").isBoolean()) {
                    proceed = root.get("continue").asBoolean();
                }
                if (root.hasNonNull("stopReason")) {
                    stopReason = root.get("stopReason").asText();
                }
                if (root.hasNonNull("systemMessage")) {
                    systemMessage = root.get("systemMessage").asText();
                }
                // permissionDecision only (never a bare top-level decision —
                // out-of-band {"decision":...} is invalid per the schemas)
                JsonNode specific = root.path("hookSpecificOutput");
                String permission = specific.path("permissionDecision").asText(null);
                if (permission != null) {
                    decision = switch (permission.toLowerCase()) {
                        case "allow", "approve" -> Decision.ALLOW;
                        case "deny", "block" -> Decision.DENY;
                        case "ask" -> Decision.ASK;
                        default -> null;
                    };
                    reason = specific.path("permissionDecisionReason").asText(null);
                }
                String context = root.path("additionalContext").asText(null);
                if (context != null && !context.isBlank()) {
                    additionalContext = context;
                }
            } else if (exitCode == null || exitCode == 0) {
                additionalContext = cleanStdout;
            }
        }
        return new HookOutput(exitCode, cleanStdout, cleanStderr, proceed, stopReason,
                decision, reason, additionalContext, systemMessage);
    }
}
