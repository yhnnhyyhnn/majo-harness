package io.majo.harness.shell;

/**
 * The shell extension point: a waterfall dispatched before every run.
 *
 * <p>Listeners run before the provider and may rewrite the command (mutate
 * {@code args[0]} and call {@code next()}) or reject the run outright by
 * throwing a {@link ShellException} without calling {@code next()}. Policy and
 * observability plugins attach here without importing the service.
 */
public final class ShellEvents {

    /** Waterfall {@code (ShellCommand command)} before a run. */
    public static final String PRE_EXECUTE = "shell/pre-execute";

    private ShellEvents() {}
}
