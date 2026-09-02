package io.majo.harness.sandbox;

import java.util.List;

/**
 * The default {@link SandboxProvider}: no confinement (argv passes through
 * unchanged). Selecting it explicitly documents that a run is unconfined; a
 * confined run swaps this provider (for example {@link BwrapSandboxProvider}
 * on Linux).
 */
public final class IdentitySandboxProvider implements SandboxProvider {

    public static final String PROVIDER_NAME = "identity";

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public List<String> confine(List<String> argv) {
        return List.copyOf(argv);
    }
}
