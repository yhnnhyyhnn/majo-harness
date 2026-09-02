package io.majo.harness.interaction;

/**
 * An approval decision. {@link #ABSTAIN} lets the next handler decide;
 * when every handler abstains the service falls back to {@link #DENY}
 * (fail-safe).
 */
public enum ApprovalDecision {
    APPROVE,
    DENY,
    ABSTAIN
}
