package io.majo.harness.interaction;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The interaction service ({@code ctx.interactions}): routes approval and
 * ask-user requests to registered {@link InteractionHandler handlers} in
 * deterministic order — {@link #registerFront} handlers decide first (a web
 * approval UI registers there), later handlers are fallbacks. Decisions are
 * fail-safe: handlers abstain by default, an unanswered approval denies, an
 * unanswered question fails loudly. Every request and its resolution is
 * broadcast for observers.
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

    private final Map<String, InteractionHandler> byName = new ConcurrentHashMap<>();
    private final List<InteractionHandler> ordered = new CopyOnWriteArrayList<>();

    public InteractionService(Context ctx) {
        super(ctx, NAME);
    }

    /** Registers a handler (appended); duplicates fail loudly. */
    public Disposable register(String name, InteractionHandler handler) {
        return insert(name, handler, false);
    }

    /** Registers a handler ahead of every existing one (first-decision wins). */
    public Disposable registerFront(String name, InteractionHandler handler) {
        return insert(name, handler, true);
    }

    private Disposable insert(String name, InteractionHandler handler, boolean front) {
        InteractionHandler previous = byName.putIfAbsent(name, handler);
        if (previous != null) {
            throw new IllegalStateException("interaction handler \"" + name + "\" has been registered");
        }
        if (front) {
            ordered.add(0, handler);
        } else {
            ordered.add(handler);
        }
        return () -> {
            byName.remove(name, handler);
            ordered.remove(handler);
        };
    }

    /** The names of registered handlers. */
    public List<String> handlerNames() {
        return List.copyOf(byName.keySet());
    }

    /** Resolves an approval through the registered handlers in order. */
    public ApprovalDecision approve(ApprovalRequest request) {
        ctx.emit(EVENT_APPROVAL_REQUEST, request);
        ApprovalDecision decision = ApprovalDecision.ABSTAIN;
        for (InteractionHandler handler : ordered) {
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
        for (InteractionHandler handler : ordered) {
            String answer = handler.answer(question);
            if (answer != null) {
                ctx.emit(EVENT_ANSWER, question, answer);
                return answer;
            }
        }
        throw new InteractionException("no interaction handler answers questions; registered: " + handlerNames());
    }
}
