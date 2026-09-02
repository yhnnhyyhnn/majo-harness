package io.majo.harness.credentials;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The env credential provider: resolves from environment variables plus an
 * optional {@code .env} file ({@code KEY=VALUE} lines, {@code #} comments,
 * optional quotes). Real environment variables override file entries. Secrets
 * stay in the value map only — they never appear in exceptions.
 */
public final class EnvCredentialProvider implements CredentialProvider {

    public static final String PROVIDER_NAME = "env";

    private final Map<String, String> secrets;

    /** Builds from an environment snapshot (injectable for tests) and a file. */
    public EnvCredentialProvider(Map<String, String> environment, Path envFile) {
        Map<String, String> merged = new HashMap<>();
        if (envFile != null) {
            merged.putAll(parse(envFile));
        }
        if (environment != null) {
            merged.putAll(environment); // real env wins over .env
        }
        this.secrets = Map.copyOf(merged);
    }

    private static Map<String, String> parse(Path envFile) {
        Map<String, String> entries = new HashMap<>();
        if (!Files.exists(envFile)) {
            throw new CredentialException("credentials: env file does not exist: " + envFile);
        }
        try {
            for (String raw : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    throw new CredentialException("credentials: malformed env line in " + envFile
                            + " (expected KEY=VALUE)");
                }
                String name = line.substring(0, equals).strip();
                String value = line.substring(equals + 1).strip();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                entries.put(name, value);
            }
            return entries;
        } catch (IOException e) {
            throw new CredentialException("credentials: cannot read env file " + envFile + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public Optional<String> resolve(String name) {
        return Optional.ofNullable(secrets.get(name));
    }
}
