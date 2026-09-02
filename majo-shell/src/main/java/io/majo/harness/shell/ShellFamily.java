package io.majo.harness.shell;

import java.util.List;
import java.util.Locale;

/**
 * Supported shell families and the factory for choosing one: a launcher is a
 * stateless {@link ShellLauncher strategy} created per family. The shipped
 * default follows the platform (Factory Method); profiles may override with a
 * config value, and an unknown family name fails loudly.
 */
public enum ShellFamily {

    BASH(List.of("/bin/bash", "-c")),
    POWERSHELL(List.of("powershell", "-NoProfile", "-Command")),
    CMD(List.of("cmd", "/c"));

    private final List<String> prefix;

    ShellFamily(List<String> prefix) {
        this.prefix = prefix;
    }

    /** The launcher for this family. */
    public ShellLauncher launcher() {
        return new FlagShellLauncher(this, prefix);
    }

    /** The platform default: PowerShell on Windows, bash elsewhere. */
    public static ShellFamily detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? POWERSHELL : BASH;
    }

    /** Parses a profile config value ({@code bash|powershell|pwsh|cmd}); unknown names fail loudly. */
    public static ShellFamily ofConfig(Object value) {
        if (value == null) {
            return detect();
        }
        String name = String.valueOf(value).toLowerCase(Locale.ROOT);
        return switch (name) {
            case "bash", "sh" -> BASH;
            case "powershell", "pwsh" -> POWERSHELL;
            case "cmd" -> CMD;
            default -> throw new IllegalArgumentException(
                    "shell: unknown family \"" + value + "\"; supported: bash, powershell, cmd");
        };
    }
}
