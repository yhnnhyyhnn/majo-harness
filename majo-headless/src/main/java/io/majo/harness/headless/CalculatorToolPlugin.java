package io.majo.harness.headless;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.tools.ToolRegistry;
import java.util.HashMap;
import java.util.Map;

/**
 * App plugin contributing the {@link CalculatorTool sample tool}: registers it
 * on {@code ctx.tools} once the tools service is live. The registration is an
 * effect — it reverts when this plugin unloads.
 */
public final class CalculatorToolPlugin implements Plugin {

    public static final String NAME = "calc";

    @Override
    public Object apply(Context ctx, Object config) {
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        return tools.register(new CalculatorTool());
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(ToolRegistry.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
