package io.majo.harness.shell;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.util.Disposables;
import java.util.HashMap;
import java.util.Map;

/**
 * The shell tool consumer: registers the {@code run_shell} tool on
 * {@code ctx.tools} once the tools and shell services are live (the consumer
 * role of the seam — further tools compose beside it via {@link Disposables}).
 */
public final class ShellToolPlugin implements Plugin {

    public static final String NAME = "shell-tools";

    @Override
    public Object apply(Context ctx, Object config) {
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        ShellService shell = ctx.get(ShellService.NAME);
        Disposable runShell = tools.register(new RunShellTool(shell));
        return Disposables.composite(runShell);
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(ToolRegistry.NAME, null);
        inject.put(ShellService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
