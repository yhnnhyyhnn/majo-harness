package io.majo.harness.webaccess;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import java.util.HashMap;
import java.util.Map;

/** Registers the anonymous HTTP(S) fetch backend on {@code ctx.web}. */
public final class FetchHttpPlugin implements Plugin {

    public static final String NAME = "web-fetch-http";

    @Override
    public Object apply(Context ctx, Object config) {
        WebAccessService web = ctx.get(WebAccessService.NAME);
        Disposable registration = web.registerFetchProvider(new FetchHttpProvider());
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
