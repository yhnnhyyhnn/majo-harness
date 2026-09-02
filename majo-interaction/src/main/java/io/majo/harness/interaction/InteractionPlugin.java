package io.majo.harness.interaction;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.util.Disposables;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mounts {@link InteractionService} as the {@code interactions} plugin with
 * handlers chosen by config:
 * <pre>
 * approval: auto | deny          # approvals; defaults to deny (fail-safe)
 * answer:   none | canned:<text> # questions; "none" (default) fails loudly
 * </pre>
 * Queueing (interactive) handlers are registered programmatically by the UI —
 * this shipped plugin stays deterministic. Unknown modes fail loudly.
 */
public final class InteractionPlugin implements Plugin {

    public static final String NAME = "interactions";

    @Override
    public Object apply(Context ctx, Object config) {
        InteractionService service = new InteractionService(ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = config instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Disposable approvals = registerApproval(service, map);
        Disposable answers = registerAnswer(service, map);
        return Disposables.composite(approvals, answers);
    }

    private static Disposable registerApproval(InteractionService service, Map<String, Object> config) {
        Object value = config.get("approval");
        String mode = value == null ? "deny" : String.valueOf(value).toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "auto" -> service.register("approval-auto",
                    new ConfiguredInteractionHandler("approval-auto", ApprovalDecision.APPROVE, null));
            case "deny" -> service.register("approval-deny",
                    new ConfiguredInteractionHandler("approval-deny", ApprovalDecision.DENY, null));
            default -> throw new IllegalArgumentException(
                    "interactions: unknown approval mode \"" + mode + "\"; supported: auto, deny");
        };
    }

    private static Disposable registerAnswer(InteractionService service, Map<String, Object> config) {
        Object value = config.get("answer");
        if (value == null) {
            return null; // no answering handler: questions fail loudly
        }
        String mode = String.valueOf(value);
        if (mode.startsWith("canned:")) {
            String text = mode.substring("canned:".length());
            return service.register("answer-canned",
                    new ConfiguredInteractionHandler("answer-canned", ApprovalDecision.ABSTAIN, text));
        }
        throw new IllegalArgumentException(
                "interactions: unknown answer mode \"" + mode + "\"; supported: none, canned:<text>");
    }

    @Override
    public String name() {
        return NAME;
    }
}
