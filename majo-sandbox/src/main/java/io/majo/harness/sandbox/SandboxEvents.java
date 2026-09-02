package io.majo.harness.sandbox;

/**
 * The sandbox extension point: a waterfall dispatched before confinement.
 *
 * <p>Listeners may rewrite the argv (mutate {@code args[0]} and call
 * {@code next()}) or reject outright by throwing a {@link SandboxException}
 * without calling {@code next()}. Policy plugins attach here without importing
 * the service.
 */
public final class SandboxEvents {

    /** Waterfall {@code (List<String> argv)} before confinement. */
    public static final String PRE_CONFINE = "sandbox/pre-confine";

    private SandboxEvents() {}
}
