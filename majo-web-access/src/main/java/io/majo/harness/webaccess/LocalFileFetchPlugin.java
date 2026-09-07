package io.majo.harness.webaccess;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers the offline {@link LocalFileFetchProvider}. Config:
 * <pre>
 * root: examples/demo-corpus   # served as file:&lt;name&gt; URLs
 * </pre>
 * The backend never touches the network, so profile boot and every fetch stay
 * fully offline (demo/e2e friendly).
 */
public final class LocalFileFetchPlugin implements Plugin {

    public static final String NAME = "web-fetch-local";

    @Override
    public Object apply(Context ctx, Object config) {
        WebAccessService web = ctx.get(WebAccessService.NAME);
        Disposable registration = web.registerFetchProvider(new LocalFileFetchProvider(
                LocalFileFetchProvider.resolveRoot(
                        config instanceof Map<?, ?> map ? map.get("root") : null)));
        return registration;
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(WebAccessService.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
