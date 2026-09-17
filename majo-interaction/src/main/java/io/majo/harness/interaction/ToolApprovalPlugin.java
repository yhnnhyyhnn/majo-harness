package io.majo.harness.interaction;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import io.majo.harness.settings.SettingsService;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolEvents;
import io.majo.harness.tools.ToolResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The approval gate: a Chain-of-Responsibility listener on
 * {@code tools/pre-execute} that pauses model tool calls behind
 * {@code ctx.interactions}. Config {@code {tools: [name, …]}} lists the gated
 * tools; an absent/empty list gates every tool. A denied call short-circuits
 * to an error result; an approved call delegates via {@code next()}.
 *
 * <p><b>Session policy</b> (dsh ask/never): the running session's
 * {@code session.approval.<sessionId>} setting (fallback: the plugin's
 * {@code defaultPolicy} config, fallback {@code ask}) decides before any
 * handler is consulted — {@code auto} grants, {@code never} deterministically
 * denies, {@code ask} defers to the handlers. Unanswered asks already deny
 * (fail-closed).
 *
 * <p><b>Durable audit pair</b> (dsh user-approval): when the call runs inside
 * a turn (the loop binds the session), every resolution lands as an
 * {@code APPROVAL_REQUESTED} + {@code APPROVAL_DECIDED} event pair wrapped by
 * the open turn — a crash mid-ask leaves a recognizable orphan. Policy grants
 * and denials are audited too, so the log shows who (or what) decided.
 */
public final class ToolApprovalPlugin implements Plugin {

    public static final String NAME = "tool-approval";

    /** Policies for a gated call, resolved before handlers run. */
    private enum Policy { ASK, AUTO, NEVER }

    @Override
    public Object apply(Context ctx, Object config) {
        InteractionService interactions = ctx.get(InteractionService.NAME);
        // soft lookups: the gate works without sessions/settings (headless
        // profiles) — it just skips the audit pair and policy override
        SessionService sessions = ctx.get(SessionService.NAME);
        SettingsService settings = ctx.get(SettingsService.NAME);
        List<String> gated = gatedTools(config);
        String defaultPolicy = defaultPolicy(config);
        ctx.on(ToolEvents.PRE_EXECUTE, (thisArg, args) -> {
            ToolCall call = (ToolCall) args[0];
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Object> next =
                    (java.util.function.Supplier<Object>) args[args.length - 1];
            if (!gated.isEmpty() && !gated.contains(call.name())) {
                return next.get(); // not gated: delegate
            }
            String sessionId = InteractionContext.sessionId();
            ApprovalRequest request = ApprovalRequest.of(
                    "run tool \"" + call.name() + "\"", call.arguments(),
                    InteractionContext.agent());
            Policy policy = resolvePolicy(sessionId, settings, defaultPolicy);
            String decision;
            String source;
            if (InteractionContext.autoApprove() || policy == Policy.AUTO) {
                decision = "allow";
                source = "policy";
            } else if (policy == Policy.NEVER) {
                decision = "deny";
                source = "policy";
            } else {
                audit(sessions, sessionId, SessionEventType.APPROVAL_REQUESTED, Map.of(
                        SessionEvent.FIELD_APPROVAL_ID, request.id(),
                        SessionEvent.FIELD_SUMMARY, request.summary(),
                        SessionEvent.FIELD_DETAILS, String.valueOf(request.details()),
                        SessionEvent.FIELD_AGENT, String.valueOf(request.agent())));
                // the ask lands before it is consulted: a crash mid-ask leaves
                // a readable orphan instead of an invisible hole
                boolean granted = interactions.approve(request) == ApprovalDecision.APPROVE;
                decision = granted ? "allow" : "deny";
                source = "handler";
            }
            audit(sessions, sessionId, SessionEventType.APPROVAL_DECIDED, Map.of(
                    SessionEvent.FIELD_APPROVAL_ID, request.id(),
                    SessionEvent.FIELD_DECISION, decision,
                    SessionEvent.FIELD_SOURCE, source));
            if ("allow".equals(decision)) {
                return next.get();
            }
            return ToolResult.error("tool \"" + call.name() + "\" requires approval and was denied");
        });
        return null;
    }

    /** Session override, then profile default, then {@code ask}. */
    private static Policy resolvePolicy(String sessionId, SettingsService settings,
            String defaultPolicy) {
        String raw = null;
        if (settings != null && sessionId != null) {
            raw = settings.get("session.approval." + sessionId);
        }
        if (raw == null && settings != null) {
            raw = settings.get("approval.policy");
        }
        if (raw == null) {
            raw = defaultPolicy;
        }
        if (raw == null) {
            return Policy.ASK;
        }
        return switch (raw.trim().toLowerCase()) {
            case "auto" -> Policy.AUTO;
            case "never" -> Policy.NEVER;
            default -> Policy.ASK;
        };
    }

    /** Appends one audit event when the call runs inside a session-bound turn. */
    private static void audit(SessionService sessions, String sessionId,
            SessionEventType type, Map<String, Object> fields) {
        if (sessions == null || sessionId == null) {
            return; // outside a turn / no session store: nothing durable to write
        }
        sessions.append(sessionId, type, fields);
    }

    private static String defaultPolicy(Object config) {
        if (config instanceof Map<?, ?> map && map.get("defaultPolicy") != null) {
            return String.valueOf(map.get("defaultPolicy"));
        }
        return null;
    }

    private static List<String> gatedTools(Object config) {
        if (!(config instanceof Map<?, ?> map)) {
            return List.of(); // gate every tool
        }
        Object raw = map.get("tools");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> tools = new ArrayList<>();
        for (Object item : list) {
            tools.add(String.valueOf(item));
        }
        return List.copyOf(tools);
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(InteractionService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
