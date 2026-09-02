package io.majo.harness.subprocess;

/**
 * The subprocess extension point: a waterfall dispatched before every run.
 *
 * <p>Listeners run before the provider and may rewrite the command (mutate
 * {@code args[0]} and call {@code next()}) or reject the run outright by
 * throwing an {@link SubprocessException} without calling {@code next()} — the
 * rejection surfaces to the caller as that same loud failure. Policy and
 * observability plugins attach here without importing the service.
 */
public final class SubprocessEvents {

    /** Waterfall {@code (Command command)} before a run. */
    public static final String PRE_EXECUTE = "subprocess/pre-execute";

    private SubprocessEvents() {}
}
