package io.majo.harness.shell;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.subprocess.SubprocessService;
import java.util.HashMap;
import java.util.Map;

/**
 * Mounts {@link ShellService} as the {@code shell} plugin: the local provider
 * adapts {@code ctx.subprocess}, so the profile must mount the subprocess row
 * beneath it. Config: {@code {shell: <family>, defaultTimeoutSeconds: <n>}}.
 */
public final class ShellPlugin implements Plugin {

    public static final String NAME = "shell";

    @Override
    public Object apply(Context ctx, Object config) {
        ShellFamily family = ShellFamily.ofConfig(config instanceof Map<?, ?> map ? map.get("shell") : null);
        SubprocessService subprocess = ctx.get(SubprocessService.NAME);
        ShellProvider provider = new LocalShellProvider(subprocess, family.launcher());
        new ShellService(ctx, provider, config);
        return null;
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(SubprocessService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
