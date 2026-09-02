package io.majo.harness.interaction;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The interaction service ({@code ctx.interactions}): routes approval and
 * ask-user requests to registered {@link InteractionHandler handlers} in
 * registration order. Decisions are fail-safe — handlers abstain by default,
 * and an unanswered approval denies while an unanswered question fails loudly.
 * Every request and its resolution is broadcast for observers.
 */
public final class InteractionService extends Service {

    public static final String NAME = "interactions";
    /** Fired on entry with {@code (ApprovalRequest)}. */
    public static final String EVENT_APPROVAL_REQUEST = "interaction/approval-request";
    /** Fired on resolution with {@code (ApprovalRequest, ApprovalDecision)}. */
    public static final String EVENT_APPROVAL = "interaction/approval";
    /** Fired on entry with {@code (Question)}. */
    public static final String EVENT_QUESTION = "interaction/question";
    /** Fired on resolution with {@code (Question, String answer)}. */
    public static final String EVENT_ANSWER = "interaction/answer";

    private final Map<String, InteractionHandler> handlers = new ConcurrentHashMap<>();

    public InteractionService(Context ctx) {
        super(ctx, NAME);
    }

    /** Registers a handler; duplicates fail loudly, disposal unregisters. */
    public Disposable register(String name, InteractionHandler handler) {
        InteractionHandler previous = handlers.putIfAbsent(name, handler);
        if (previous != null) {
            throw new IllegalStateException("interaction handler \"" + name + "\" has been registered");
        }
        return () -> handlers.remove(name, handler);
    }

    /** The names of registered handlers. */
    public List<String> handlerNames() {
        return List.copyOf(handlers.keySet());
    }

    /** Resolves an approval through the registered handlers in order. */
    public ApprovalDecision approve(ApprovalRequest request) {
        ctx.emit(EVENT_APPROVAL_REQUEST, request);
        ApprovalDecision decision = ApprovalDecision.ABSTAIN;
        for (InteractionHandler handler : handlers.values()) {
            ApprovalDecision candidate = handler.approve(request);
            if (candidate != ApprovalDecision.ABSTAIN) {
                decision = candidate;
                break;
            }
        }
        if (decision == ApprovalDecision.ABSTAIN) {
            decision = ApprovalDecision.DENY; // fail-safe
        }
        ctx.emit(EVENT_APPROVAL, request, decision);
        return decision;
    }

    /** Resolves an ask-user question; no answering handler fails loudly. */
    public String ask(Question question) {
        ctx.emit(EVENT_QUESTION, question);
        for (InteractionHandler handler : handlers.values()) {
            String answer = handler.answer(question);
            if (answer != null) {
                ctx.emit(EVENT_ANSWER, question, answer);
                return answer;
            }
        }
        throw new InteractionException("no interaction handler answers questions; registered: " + handlers.keySet());
    }
}
