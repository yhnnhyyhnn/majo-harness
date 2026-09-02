package io.majo.harness.sandbox;

import java.util.List;

/**
 * The sandbox provider seam (Service Definition): implementations confine an
 * argv list before spawning, returning a possibly-wrapped argv (mirroring the
 * dsh sandbox backends that wrap argv; consumers apply the wrap before
 * spawning). The {@link IdentitySandboxProvider} ships as the default — real
 * confinement is a provider swap (Linux can use the {@link BwrapSandboxProvider}).
 *
 * <p>Providers must throw {@link SandboxException} when they cannot confine.
 */
public interface SandboxProvider {

    /** A human-readable provider name for diagnostics. */
    String name();

    /** Returns the confined argv for {@code argv}. */
    List<String> confine(List<String> argv);
}
