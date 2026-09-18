package io.majo.harness.subprocess;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/**
 * Mounts {@link SubprocessService} as the {@code subprocess} plugin.
 *
 * <p>Config: {@code {defaultTimeoutSeconds: <n>, ssh: {host, user, port?,
 * identityFile?}}} — when {@code ssh} is present, commands execute on the
 * remote host via OpenSSH instead of locally (dsh `subprocess-ssh`).
 */
public final class SubprocessPlugin implements Plugin {

    public static final String NAME = "subprocess";

    @Override
    public Object apply(Context ctx, Object config) {
        SubprocessProvider provider;
        if (config instanceof java.util.Map<?, ?> map
                && map.get("ssh") instanceof java.util.Map<?, ?> ssh) {
            var target = io.majo.harness.ssh.SshTarget.fromConfig(ssh);
            long timeoutMillis = 60_000;
            if (map.get("defaultTimeoutSeconds") instanceof Number number
                    && number.longValue() > 0) {
                timeoutMillis = number.longValue() * 1000;
            }
            provider = new SshSubprocessProvider(
                    new io.majo.harness.ssh.SshExec(target, timeoutMillis));
        } else {
            provider = new LocalSubprocessProvider();
        }
        new SubprocessService(ctx, provider, config);
        return null;
    }

    @Override
    public String name() {
        return NAME;
    }
}
