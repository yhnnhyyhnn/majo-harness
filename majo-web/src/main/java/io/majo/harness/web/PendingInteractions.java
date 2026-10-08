package io.majo.harness.web;

import io.majo.harness.interaction.ApprovalDecision;
import io.majo.harness.interaction.ApprovalRequest;
import io.majo.harness.interaction.InteractionHandler;
import io.majo.harness.interaction.Question;
import java.util.List;

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
        // a timed question that already returned pending to the model: the
        // late answer delivers into the session as a marked user message
        TimedQuestion timed = timedQuestions.get(id);
        if (timed != null) {
            timedQuestions.remove(id);
            questions.remove(id);
            if (loop != null && timed.sessionId() != null) {
                loop.followup(timed.sessionId(),
                        "[answer_to_pending_question] question: " + timed.text()
                                + "\nanswer: " + text);
            }
        }
        return true;
    }

    // ----- timed asks (dsh tool-ask-user timed parity) -----

    /** One timed question's bookkeeping (late-answer delivery). */
    public record TimedQuestion(String id, String text, long askedAtMs, String sessionId) {}

    private final java.util.Map<String, TimedQuestion> timedQuestions =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile io.majo.harness.agent.loop.AgentLoopService loop;

    /** Wires the loop for late-answer delivery (idempotent). */
    public void bindLoop(io.majo.harness.agent.loop.AgentLoopService agentLoop) {
        this.loop = agentLoop;
    }

    /**
     * Registers a timed question: fires the notifier, waits at most
     * {@code timeoutSeconds} (-1 = indefinitely), and returns the answer or
     * {@code null} on timeout. The registration stays for late answers until
     * one arrives or the question is resolved.
     */
    public String askTimed(Question question, String sessionId, long timeoutSeconds) {
        java.util.concurrent.CompletableFuture<String> future =
                new java.util.concurrent.CompletableFuture<>();
        questions.put(question.id(), future);
        timedQuestions.put(question.id(), new TimedQuestion(
                question.id(), question.text(), System.currentTimeMillis(), sessionId));
        Notifier active = notifier.get();
        if (active != null) {
            active.question(question);
        }
        try {
            if (timeoutSeconds < 0) {
                String answer = future.get();
                return answer == null ? "" : answer;
            }
            String answer = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            return answer == null ? "" : answer;
        } catch (java.util.concurrent.TimeoutException e) {
            return null; // timed out: the turn continues, the late answer may still arrive
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * A late answer for a timed question: delivered into the session as a
     * marked user follow-up (dsh answer_to_pending_question parity). Returns
     * {@code false} when the id is unknown (never asked or already answered).
     */
    public boolean answerTimed(String id, String text) {
        TimedQuestion question = timedQuestions.get(id);
        java.util.concurrent.CompletableFuture<String> future = questions.get(id);
        if (question == null || future == null) {
            return false;
        }
        boolean completed = future.complete(text);
        timedQuestions.remove(id);
        questions.remove(id);
        if (completed && loop != null && question.sessionId() != null) {
            loop.followup(question.sessionId(),
                    "[answer_to_pending_question] question: " + question.text()
                            + "\nanswer: " + text);
        }
        return completed;
    }

    /** Timed questions still awaiting a late answer. */
    public java.util.List<TimedQuestion> openTimedQuestions() {
        return List.copyOf(timedQuestions.values());
    }
}
