package io.majo.harness.sandbox;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.List;
import java.util.Map;

/**
 * Mounts {@link SandboxService} as the {@code sandbox} plugin. The provider is
 * chosen by factory from config:
 * <pre>
 * provider: identity                  # default: no confinement
 * provider: bwrap                     # Linux bubblewrap confinement
 *   bwrapExecutable: /usr/bin/bwrap   # optional
 *   bwrapOptions:                     # required for bwrap
 *     - --unshare-all
 *     - --ro-bind
 *     - /
 * </pre>
 * Unknown provider names fail loudly.
 */
public final class SandboxPlugin implements Plugin {

    public static final String NAME = "sandbox";

    @Override
    public Object apply(Context ctx, Object config) {
        new SandboxService(ctx, providerOf(config));
        return null;
    }

    static SandboxProvider providerOf(Object config) {
        if (!(config instanceof Map<?, ?> map)) {
            return new IdentitySandboxProvider();
        }
        Object value = map.get("provider");
        String name = value == null ? IdentitySandboxProvider.PROVIDER_NAME : String.valueOf(value);
        return switch (name) {
            case IdentitySandboxProvider.PROVIDER_NAME -> new IdentitySandboxProvider();
            case BwrapSandboxProvider.PROVIDER_NAME -> {
                Object executable = map.get("bwrapExecutable");
                Object options = map.get("bwrapOptions");
                if (!(options instanceof List<?> raw) || raw.isEmpty()) {
                    throw new SandboxException("sandbox: bwrap provider requires non-empty bwrapOptions");
                }
                List<String> opts = raw.stream().map(String::valueOf).toList();
                yield executable == null
                        ? new BwrapSandboxProvider(opts)
                        : new BwrapSandboxProvider(String.valueOf(executable), opts);
            }
            default -> throw new SandboxException("sandbox: unknown provider \"" + name + "\"; supported: identity, bwrap");
        };
    }

    @Override
    public String name() {
        return NAME;
    }
}
