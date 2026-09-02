package io.majo.harness.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A queueing {@link InteractionHandler}: pending approvals and questions block
 * until a decision or answer is submitted (the interactive/human channel —
 * headless modes use the {@code auto}/{@code deny}/{@code canned} handlers
 * instead). Used by the UI or test harness to drive real human decisions.
 */
public final class QueueingInteractionHandler implements InteractionHandler {

    private final BlockingQueue<ApprovalRequest> approvals = new LinkedBlockingQueue<>();
    private final BlockingQueue<Question> questions = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> decisions = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
    private final String name;

    public QueueingInteractionHandler(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ApprovalDecision approve(ApprovalRequest request) {
        approvals.add(request);
        try {
            String decision = decisions.poll(60, TimeUnit.SECONDS);
            if (decision == null) {
                return ApprovalDecision.DENY;
            }
            return "approve".equalsIgnoreCase(decision) ? ApprovalDecision.APPROVE : ApprovalDecision.DENY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApprovalDecision.DENY;
        }
    }

    @Override
    public String answer(Question question) {
        questions.add(question);
        try {
            String answer = answers.poll(60, TimeUnit.SECONDS);
            return answer == null ? "" : answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** Pending approval requests (for surfacing to a human). */
    public List<ApprovalRequest> pendingApprovals() {
        return List.copyOf(approvals);
    }

    /** Pending questions (for surfacing to a human). */
    public List<Question> pendingQuestions() {
        return List.copyOf(questions);
    }

    /** Submits the human's approval decision for the next pending request. */
    public void submitApproval(boolean granted) {
        decisions.add(granted ? "approve" : "deny");
    }

    /** Submits the human's answer for the next pending question. */
    public void submitAnswer(String text) {
        answers.add(text);
    }
}
