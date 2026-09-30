package io.majo.harness.hooks;

/**
 * Hook extension points for other modules: the waterfall events the hooks
 * plugin itself consumes are {@code tools/pre-execute} (blocking gate) and
 * {@code agent/user-submit} (the loop fires it with {@code (sessionId,
 * userText)} before the message becomes durable — listeners may return a
 * {@code String} replacement or throw to reject).
 */
public final class HookEvents {

    private HookEvents() {}
}
