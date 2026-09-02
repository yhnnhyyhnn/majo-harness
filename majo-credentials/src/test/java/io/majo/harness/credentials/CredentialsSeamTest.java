package io.majo.harness.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialsSeamTest {

    @Test
    void envProviderReadsEnvAndFileWithEnvWinning(@TempDir Path dir) throws Exception {
        Path envFile = dir.resolve(".env");
        java.nio.file.Files.writeString(envFile, """
                # demo credentials
                KEY_A=from-file
                KEY_B="quoted value"
                KEY_OVERRIDDEN=file-value
                """);
        Map<String, String> environment = Map.of("KEY_OVERRIDDEN", "env-value", "KEY_C", "env-only");

        EnvCredentialProvider provider = new EnvCredentialProvider(environment, envFile);
        assertThat(provider.resolve("KEY_A")).contains("from-file");
        assertThat(provider.resolve("KEY_B")).contains("quoted value");
        assertThat(provider.resolve("KEY_OVERRIDDEN")).contains("env-value"); // real env wins
        assertThat(provider.resolve("KEY_C")).contains("env-only");
        assertThat(provider.resolve("MISSING")).isEmpty();

        assertThatThrownBy(() -> new EnvCredentialProvider(Map.of(), dir.resolve("gone.env")))
                .isInstanceOf(CredentialException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void envFileMalformedLineFailsLoud(@TempDir Path dir) throws Exception {
        Path envFile = dir.resolve(".env");
        java.nio.file.Files.writeString(envFile, "NO_EQUALS_HERE\n");
        assertThatThrownBy(() -> new EnvCredentialProvider(Map.of(), envFile))
                .isInstanceOf(CredentialException.class)
                .hasMessageContaining("KEY=VALUE");
    }

    @Test
    void serviceResolvesInOrderAndFailsWithoutLeaking(@TempDir Path dir) throws Exception {
        Path envFile = dir.resolve(".env");
        java.nio.file.Files.writeString(envFile, "SHARED=file-secret\nONLY_FILE=secret-file\n");
        Context ctx = Context.create();
        ctx.plugin(new CredentialsPlugin(), Map.of("envFile", envFile.toString(), "sourceEnv", false))
                .await().join();
        CredentialsService credentials = ctx.get(CredentialsService.NAME);
        assertThat(credentials.providerNames()).containsExactly("env");

        assertThat(credentials.resolve("ONLY_FILE")).isEqualTo("secret-file");
        assertThat(credentials.has("SHARED")).isTrue();
        assertThat(credentials.has("NOTHERE")).isFalse();
        Throwable missing = org.assertj.core.api.Assertions.catchThrowable(() -> credentials.resolve("NOTHERE"));
        assertThat(missing).isInstanceOf(CredentialException.class);
        assertThat(missing.getMessage()).contains("NOTHERE").doesNotContain("secret-file");

        // a second provider can fall back without values leaking
        CredentialsService service = ctx.get(CredentialsService.NAME);
        service.register(new CredentialProvider() {
            @Override
            public String name() {
                return "fallback";
            }

            @Override
            public Optional<String> resolve(String name) {
                return "special".equals(name) ? Optional.of("value") : Optional.empty();
            }
        });
        assertThat(service.resolve("special")).isEqualTo("value");
        ctx.fiber().disposeAsync().join();
    }
}
