package io.majo.harness.util;

import io.jcordis.core.util.Disposable;
import java.util.ArrayList;
import java.util.List;

/**
 * Factory of {@link Disposable composites} (the Composite pattern): plugin
 * bodies that contribute several reversible registrations (tools, models,
 * projections, listeners) return one combined disposer so the fiber reverts
 * every contribution on unload — no ad-hoc wrapper lambdas at call sites.
 */
public final class Disposables {

    private Disposables() {}

    /** Combines disposables, reverting all in reverse order; nulls are skipped. */
    public static Disposable composite(Disposable... disposables) {
        List<Disposable> kept = new ArrayList<>();
        if (disposables != null) {
            for (Disposable disposable : disposables) {
                if (disposable != null) {
                    kept.add(disposable);
                }
            }
        }
        return composite(kept);
    }

    /** Combines disposables, reverting all in reverse order. */
    public static Disposable composite(List<Disposable> disposables) {
        List<Disposable> kept = List.copyOf(disposables);
        return () -> {
            for (int i = kept.size() - 1; i >= 0; i--) {
                kept.get(i).dispose();
            }
        };
    }
}
