package io.majo.harness.web;

import io.majo.harness.interaction.ApprovalDecision;
import io.majo.harness.interaction.ApprovalRequest;
import io.majo.harness.interaction.InteractionHandler;
import io.majo.harness.interaction.Question;

/**
 * Web-facing {@link InteractionHandler}: approval and ask-user requests are
 * surfaced to the connected SSE client and park until a decision arrives
 * (timeout via {@code majo.approvalTimeoutSeconds}), then fail safe
 * (deny / empty answer). Registered at the front so it always decides before
 * static fallback handlers.
 */
public final class PendingInteractions implements InteractionHandler {
    private final long timeoutSeconds;

    public interface Notifier {
        void approval(ApprovalRequest request);

        void question(Question question);
    }

    private final java.util.Map<String, java.util.concurrent.CompletableFuture<ApprovalDecision>> approvals =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, java.util.concurrent.CompletableFuture<String>> questions =
            new java.util.concurrent.ConcurrentHashMap<>();

    PendingInteractions(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
    /**
     * Per-stream notifier carried by the handling thread (and inherited by
     * child virtual threads spawned inside a turn), so concurrent turns on
     * different sessions route approvals/questions to their own SSE stream.
     */
    public final java.lang.InheritableThreadLocal<Notifier> notifier =
            new java.lang.InheritableThreadLocal<>();

    @Override
    public String name() {
        return "web-ui";
    }

    @Override
    public ApprovalDecision approve(ApprovalRequest request) {
        java.util.concurrent.CompletableFuture<ApprovalDecision> future =
                new java.util.concurrent.CompletableFuture<>();
        approvals.put(request.id(), future);
        Notifier active = notifier.get();
        if (active != null) {
            active.approval(request);
        }
        try {
            ApprovalDecision decision = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            return decision == null ? ApprovalDecision.DENY : decision;
        } catch (Exception e) {
            return ApprovalDecision.DENY; // fail safe
        } finally {
            approvals.remove(request.id());
        }
    }

    @Override
    public String answer(Question question) {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        questions.put(question.id(), future);
        Notifier active = notifier.get();
        if (active != null) {
            active.question(question);
        }
        try {
            String answer = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            return answer == null ? "" : answer;
        } catch (Exception e) {
            return "";
        } finally {
            questions.remove(question.id());
        }
    }

    /** Completes a pending approval; false when unknown/expired. */
    public boolean decideApproval(String id, boolean granted) {
        java.util.concurrent.CompletableFuture<ApprovalDecision> future = approvals.get(id);
        if (future == null) {
            return false;
        }
        future.complete(granted ? ApprovalDecision.APPROVE : ApprovalDecision.DENY);
        return true;
    }

    /** Completes a pending question; false when unknown/expired. */
    public boolean answerQuestion(String id, String text) {
        java.util.concurrent.CompletableFuture<String> future = questions.get(id);
        if (future == null) {
            return false;
        }
        future.complete(text);
        return true;
    }
}
