package io.majo.harness.plan;

import io.majo.harness.session.SessionProjection;
import io.majo.harness.session.TypedSessionEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code plan} projection: folds {@code PLAN_SET} events (dsh plan-mode)
 * into the per-session plan state. "Active" means the model is drafting a
 * plan and must call {@code exit_plan_mode} for human review before doing
 * the work; approval records the deactivation.
 */
public final class PlanState implements SessionProjection {

    public static final String KEY = "plan";

    /** Immutable per-session plan snapshot. */
    public record Snapshot(boolean active, String plan) {

        public static final Snapshot NONE = new Snapshot(false, null);
    }

    private final Map<String, Snapshot> state = new ConcurrentHashMap<>();

    @Override
    public void onEvent(String sessionId, TypedSessionEvent event) {
        if (event instanceof TypedSessionEvent.PlanSet set) {
            state.put(sessionId, new Snapshot(set.active(), set.plan()));
        }
    }

    /** The current plan snapshot (inactive/empty when never set). */
    public Snapshot snapshot(String sessionId) {
        return state.getOrDefault(sessionId, Snapshot.NONE);
    }
}
