package io.majo.harness.credentials;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import io.jcordis.core.util.Disposable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The credential service ({@code ctx.credentials}): resolves secrets through
 * registered {@link CredentialProvider providers} in registration order (the
 * first non-empty answer wins). A missing credential fails loudly without
 * leaking which names exist; values never enter messages.
 */
public final class CredentialsService extends Service {

    public static final String NAME = "credentials";

    private final Map<String, CredentialProvider> providers = new LinkedHashMap<>();

    public CredentialsService(Context ctx) {
        super(ctx, NAME);
    }

    /** Registers a provider; duplicates fail loudly, disposal unregisters. */
    public Disposable register(CredentialProvider provider) {
        CredentialProvider previous = providers.putIfAbsent(provider.name(), provider);
        if (previous != null) {
            throw new IllegalStateException("credential provider \"" + provider.name() + "\" has been registered");
        }
        return () -> providers.remove(provider.name());
    }

    /** The names of registered providers. */
    public List<String> providerNames() {
        return List.copyOf(providers.keySet());
    }

    /** Whether any registered provider resolves {@code name}. */
    public boolean has(String name) {
        return providers.values().stream()
                .map(provider -> provider.resolve(name))
                .anyMatch(Optional::isPresent);
    }

    /** Resolves {@code name}, failing loudly (without values) when unknown. */
    public String resolve(String name) {
        for (CredentialProvider provider : providers.values()) {
            Optional<String> value = provider.resolve(name);
            if (value.isPresent()) {
                return value.get();
            }
        }
        throw new CredentialException("credential \"" + name + "\" is not configured");
    }
}
