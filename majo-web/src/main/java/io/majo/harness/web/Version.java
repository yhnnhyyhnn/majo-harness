package io.majo.harness.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Build version, injected by Maven resource filtering into
 * {@code majo/version.properties} — no hardcoded copy in code.
 */
public final class Version {

    private static final String UNKNOWN = "unknown";
    private static volatile String cached;

    private Version() {
    }

    public static String get() {
        String known = cached;
        if (known != null) {
            return known;
        }
        String read = UNKNOWN;
        try (InputStream stream = Version.class.getClassLoader()
                .getResourceAsStream("majo/version.properties")) {
            if (stream != null) {
                Properties props = new Properties();
                props.load(stream);
                String value = props.getProperty("version");
                if (value != null && !value.isBlank() && !value.startsWith("${")) {
                    read = value.trim();
                }
            }
        } catch (IOException ignored) {
            // fall through to "unknown"; the version is informational only
        }
        cached = read;
        return read;
    }
}
