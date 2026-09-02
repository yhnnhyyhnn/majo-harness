package io.majo.harness.credentials;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import java.nio.file.Path;
import java.util.Map;

/**
 * Mounts {@link CredentialsService} as the {@code credentials} plugin with the
 * env provider. Config: {@code {envFile: <path>}} optionally adds a
 * {@code .env} file under real environment variables. Unknown provider names
 * fail loudly.
 */
public final class CredentialsPlugin implements Plugin {

    public static final String NAME = "credentials";

    @Override
    public Object apply(Context ctx, Object config) {
        Map<?, ?> map = config instanceof Map<?, ?> m ? m : Map.of();
        Object providerName = map.get("provider");
        if (providerName != null && !EnvCredentialProvider.PROVIDER_NAME.equals(String.valueOf(providerName))) {
            throw new CredentialException("credentials: unknown provider \"" + providerName
                    + "\"; supported: " + EnvCredentialProvider.PROVIDER_NAME);
        }
        Path envFile = map.get("envFile") == null ? null : Path.of(String.valueOf(map.get("envFile")));
        Object sourceEnv = map.containsKey("sourceEnv") ? map.get("sourceEnv") : Boolean.TRUE;
        Map<String, String> environment = Boolean.TRUE.equals(sourceEnv) ? System.getenv() : Map.of();
        CredentialsService service = new CredentialsService(ctx);
        Disposable registration = service.register(new EnvCredentialProvider(environment, envFile));
        return registration;
    }

    @Override
    public String name() {
        return NAME;
    }
}
