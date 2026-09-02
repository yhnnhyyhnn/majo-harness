package io.majo.harness.interaction;

/**
 * A configured simple {@link InteractionHandler}: always approves (or always
 * denies) approvals, and optionally answers questions with a canned text.
 * Abstains on anything it is not configured for.
 */
public final class ConfiguredInteractionHandler implements InteractionHandler {

    private final String name;
    private final ApprovalDecision decision;
    private final String cannedAnswer;

    public ConfiguredInteractionHandler(String name, ApprovalDecision decision, String cannedAnswer) {
        this.name = name;
        this.decision = decision;
        this.cannedAnswer = cannedAnswer;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ApprovalDecision approve(ApprovalRequest request) {
        return decision;
    }

    @Override
    public String answer(Question question) {
        return cannedAnswer;
    }
}
