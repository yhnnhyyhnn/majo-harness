package io.majo.harness.fs;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/**
 * Mounts {@link FileSystemService} as the {@code fs} plugin.
 *
 * <p>Config: {@code {ssh: {host, user, port?, identityFile?}}} — when
 * {@code ssh} is present, file operations execute on the remote host via
 * OpenSSH instead of locally (dsh `fs-ssh`; the invariant "local path
 * access is never inferred from a remote path string" applies).
 */
public final class FsPlugin implements Plugin {

    public static final String NAME = "fs";

    @Override
    public Object apply(Context ctx, Object config) {
        if (config instanceof java.util.Map<?, ?> map
                && map.get("ssh") instanceof java.util.Map<?, ?> ssh) {
            var target = io.majo.harness.ssh.SshTarget.fromConfig(ssh);
            var exec = new io.majo.harness.ssh.SshExec(target, 30_000);
            new FileSystemService(ctx, new SshFsProvider(exec));
        } else {
            new FileSystemService(ctx);
        }
        return null;
    }

    @Override
    public String name() {
        return NAME;
    }
}
