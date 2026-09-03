package io.majo.harness.webaccess;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.util.Disposables;
import java.util.HashMap;
import java.util.Map;

/**
 * The web tool consumers (mirror of dsh {@code tool-web}): registers
 * {@code web_search} and {@code web_fetch} on {@code ctx.tools} once the tools
 * and web services are live. Tools stay visible even when no provider is
 * mounted — execution then returns a structured error the model can read.
 */
public final class WebToolsPlugin implements Plugin {

    public static final String NAME = "web-tools";

    @Override
    public Object apply(Context ctx, Object config) {
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        WebAccessService web = ctx.get(WebAccessService.NAME);
        Disposable search = tools.register(new WebSearchTool(web));
        Disposable fetch = tools.register(new WebFetchTool(web));
        return Disposables.composite(search, fetch);
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(ToolRegistry.NAME, null);
        inject.put(WebAccessService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
