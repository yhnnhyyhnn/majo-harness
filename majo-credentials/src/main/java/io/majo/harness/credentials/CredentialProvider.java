package io.majo.harness.credentials;

import java.util.Optional;

/**
 * The credential provider seam (Service Definition): resolves a secret by
 * name, or abstains with {@link Optional#empty()} so the next provider can
 * try. Providers must never include values in exceptions.
 */
public interface CredentialProvider {

    /** A human-readable provider name for diagnostics. */
    String name();

    /** Resolves {@code name}, or {@link Optional#empty()} when unknown here. */
    Optional<String> resolve(String name);
}
