package io.majo.harness.plan;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.interaction.InteractionService;
import io.majo.harness.session.SessionProjections;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.util.Disposables;
import java.util.HashMap;
import java.util.Map;

/**
 * Mounts plan mode (dsh plan-mode): registers the {@code plan} projection
 * unit (fold of PLAN_SET events) and the {@code exit_plan_mode} tool. The
 * host-side {@code /plan} command that flips the state lives with the web
 * host (same pattern as {@code status}/{@code delegate}); unloading here
 * reverts the projection and the tool.
 */
public final class PlanPlugin implements Plugin {

    public static final String NAME = "plan";

    @Override
    public Object apply(Context ctx, Object config) {
        SessionService sessions = ctx.get(SessionService.NAME);
        InteractionService interactions = ctx.get(InteractionService.NAME);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        SessionProjections projections = ctx.get(SessionProjections.NAME);
        Disposable projection = projections.register(PlanState.KEY, new PlanState());
        Disposable tool = tools.register(
                new ExitPlanModeTool(sessions, interactions, projections.require(PlanState.KEY)));
        return Disposables.composite(projection, tool);
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(SessionService.NAME, null);
        inject.put(InteractionService.NAME, null);
        inject.put(ToolRegistry.NAME, null);
        inject.put(SessionProjections.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
