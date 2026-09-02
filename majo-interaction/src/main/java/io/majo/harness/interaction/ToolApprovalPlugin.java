package io.majo.harness.interaction;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
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
 */
public final class ToolApprovalPlugin implements Plugin {

    public static final String NAME = "tool-approval";

    @Override
    public Object apply(Context ctx, Object config) {
        InteractionService interactions = ctx.get(InteractionService.NAME);
        List<String> gated = gatedTools(config);
        ctx.on(ToolEvents.PRE_EXECUTE, (thisArg, args) -> {
            ToolCall call = (ToolCall) args[0];
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Object> next =
                    (java.util.function.Supplier<Object>) args[args.length - 1];
            if (!gated.isEmpty() && !gated.contains(call.name())) {
                return next.get(); // not gated: delegate
            }
            ApprovalRequest request = ApprovalRequest.of(
                    "run tool \"" + call.name() + "\"", call.arguments());
            if (interactions.approve(request) == ApprovalDecision.APPROVE) {
                return next.get();
            }
            return ToolResult.error("tool \"" + call.name() + "\" requires approval and was denied");
        });
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
