package io.majo.harness.session;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The session projection seam ({@code ctx.sessionProjections}), mirroring dsh's
 * projection registry: registered {@link SessionProjection units} fold
 * committed events incrementally, and host consumers read their typed state.
 *
 * <p>Units are fed two ways, deduplicated by per-session sequence watermark:
 * <ul>
 *   <li><b>live</b> — every {@code session/event} broadcast folds through;</li>
 *   <li><b>replay</b> — {@link #replay} folds the stored events of a session,
 *       so a unit attached after the fact (or rebuilt after a restart)
 *       converges to the same state.</li>
 * </ul>
 *
 * <p>Registrations are reversible: plugin bodies return the {@link #register}
 * disposer, so a unit unregisters when its contributor unloads. Reads fail
 * loudly when the required unit is absent.
 */
public final class SessionProjections extends Service {

    public static final String NAME = "sessionProjections";

    private final SessionService sessions;
    private final Map<String, SessionProjection> units = new ConcurrentHashMap<>();
    /** last folded seq per session per unit (live and replay share it) */
    private final Map<String, Map<SessionProjection, Long>> watermarks = new ConcurrentHashMap<>();

    public SessionProjections(Context ctx, SessionService sessions) {
        super(ctx, NAME);
        this.sessions = sessions;
        // live feed: every durable append reaches registered units
        ctx.on(SessionService.EVENT, (thisArg, args) -> {
            fold((String) args[0], (SessionEvent) args[1]);
            return null;
        });
    }

    /**
     * Registers a unit under {@code key}; duplicates fail loudly. Plugin bodies
     * return the disposer so the unit reverts when the contributor unloads.
     */
    public Disposable register(String key, SessionProjection unit) {
        SessionProjection previous = units.putIfAbsent(key, unit);
        if (previous != null) {
            throw new IllegalStateException("session projection \"" + key + "\" has been registered");
        }
        return () -> {
            units.remove(key, unit);
            watermarks.values().forEach(session -> session.remove(unit));
        };
    }

    /** Whether a unit is registered. */
    public boolean has(String key) {
        return units.containsKey(key);
    }

    /** The registered unit, failing loudly when absent. */
    public SessionProjection unit(String key) {
        SessionProjection unit = units.get(key);
        if (unit == null) {
            throw new IllegalStateException("session projection \"" + key + "\" is not registered");
        }
        return unit;
    }

    /**
     * Reads a unit by its concrete type (host consumers depend on the unit
     * class for typed state access). Fails loudly when absent.
     */
    @SuppressWarnings("unchecked")
    public <T extends SessionProjection> T require(String key) {
        return (T) unit(key);
    }

    /**
     * Folds every stored event of {@code sessionId} into all units (idempotent
     * via per-unit sequence watermarks), so late-attached units converge.
     */
    public void replay(String sessionId) {
        for (SessionEvent event : sessions.events(sessionId)) {
            fold(sessionId, event);
        }
    }

    /** Forgets fold watermarks for a removed session (units keep derived state). */
    public void drop(String sessionId) {
        watermarks.remove(sessionId);
    }

    private void fold(String sessionId, SessionEvent event) {
        TypedSessionEvent typed = TypedSessionEvent.of(event);
        for (Map.Entry<String, SessionProjection> entry : units.entrySet()) {
            SessionProjection unit = entry.getValue();
            Map<SessionProjection, Long> seen = watermarks.computeIfAbsent(sessionId,
                    ignored -> new ConcurrentHashMap<>());
            Long last = seen.get(unit);
            if (last != null && last >= event.seq()) {
                continue;
            }
            seen.put(unit, event.seq());
            unit.onEvent(sessionId, typed);
        }
    }
}
