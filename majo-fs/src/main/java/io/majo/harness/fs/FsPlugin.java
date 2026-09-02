package io.majo.harness.fs;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;

/** Mounts {@link FileSystemService} as the {@code fs} plugin. */
public final class FsPlugin implements Plugin {

    public static final String NAME = "fs";

    @Override
    public Object apply(Context ctx, Object config) {
        new FileSystemService(ctx);
        return null;
    }

    @Override
    public String name() {
        return NAME;
    }
}
