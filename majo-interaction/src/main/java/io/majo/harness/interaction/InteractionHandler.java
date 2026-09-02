package io.majo.harness.interaction;

/**
 * An interaction handler (the Strategy seam of {@code ctx.interactions}):
 * decides approvals and answers questions. The interface defaults are
 * fail-safe — abstaining from approvals and declining to answer — so
 * observational handlers never decide by accident.
 */
public interface InteractionHandler {

    /** A human-readable handler name for diagnostics. */
    String name();

    /** The approval decision, or {@link ApprovalDecision#ABSTAIN} to defer. */
    default ApprovalDecision approve(ApprovalRequest request) {
        return ApprovalDecision.ABSTAIN;
    }

    /**
     * The answer text, or {@code null} to abstain; when no handler answers,
     * the service fails loudly.
     */
    default String answer(Question question) {
        return null;
    }
}
