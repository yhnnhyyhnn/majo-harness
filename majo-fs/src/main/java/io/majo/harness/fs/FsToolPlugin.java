package io.majo.harness.fs;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.tools.ToolRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The fs tool consumer: registers the model-facing {@code read_file} tool on
 * {@code ctx.tools} once the tools and fs services are live (the consumer role
 * of the fs seam — a write/glob tool can be added beside it the same way).
 * The combined disposer is returned from {@code apply}, so unregistering
 * reverts with this plugin.
 */
public final class FsToolPlugin implements Plugin {

    public static final String NAME = "fs-tools";

    @Override
    public Object apply(Context ctx, Object config) {
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        FileSystemService fs = ctx.get(FileSystemService.NAME);
        Disposable read = tools.register(new ReadFileTool(fs));
        return (Disposable) () -> read.dispose();
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(ToolRegistry.NAME, null);
        inject.put(FileSystemService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
