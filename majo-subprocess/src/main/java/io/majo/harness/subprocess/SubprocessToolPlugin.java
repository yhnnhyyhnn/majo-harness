package io.majo.harness.subprocess;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.tools.ToolRegistry;
import java.util.HashMap;
import java.util.Map;

/**
 * The subprocess tool consumer: registers the {@code run_command} tool on
 * {@code ctx.tools} once the tools and subprocess services are live (the
 * consumer role of the seam). The disposer is returned from {@code apply}, so
 * unregistering reverts with this plugin.
 */
public final class SubprocessToolPlugin implements Plugin {

    public static final String NAME = "subprocess-tools";

    @Override
    public Object apply(Context ctx, Object config) {
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        SubprocessService subprocess = ctx.get(SubprocessService.NAME);
        Disposable registration = tools.register(new RunCommandTool(subprocess));
        return (Disposable) () -> registration.dispose();
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(ToolRegistry.NAME, null);
        inject.put(SubprocessService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
