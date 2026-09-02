package io.majo.harness.shell;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.majo.harness.sandbox.SandboxService;
import io.majo.harness.subprocess.SubprocessService;
import java.util.HashMap;
import java.util.Map;

/**
 * Mounts {@link ShellService} as the {@code shell} plugin: the local provider
 * adapts {@code ctx.subprocess}, so the profile must mount the subprocess row
 * beneath it. Config: {@code {shell: <family>, defaultTimeoutSeconds: <n>,
 * confine: <bool>}} — with {@code confine: true} the provider wraps argv
 * through {@code ctx.sandbox} before spawning (the sandbox row must be
 * mounted too, and an absent sandbox service fails loudly).
 */
public final class ShellPlugin implements Plugin {

    public static final String NAME = "shell";

    @Override
    public Object apply(Context ctx, Object config) {
        ShellFamily family = ShellFamily.ofConfig(config instanceof Map<?, ?> map ? map.get("shell") : null);
        SubprocessService subprocess = ctx.get(SubprocessService.NAME);
        SandboxService sandbox = null;
        if (config instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("confine"))) {
            sandbox = ctx.get(SandboxService.NAME);
            if (sandbox == null) {
                throw new IllegalArgumentException(
                        "shell: confine requires the sandbox plugin to be mounted (row \"sandbox\")");
            }
        }
        ShellProvider provider = new LocalShellProvider(subprocess, family.launcher(), sandbox);
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
