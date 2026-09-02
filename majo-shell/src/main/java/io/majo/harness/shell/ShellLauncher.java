package io.majo.harness.shell;

import java.util.ArrayList;
import java.util.List;

/**
 * Shell selection strategy (the Strategy pattern): turns a command-line script
 * into an argv list for its {@link ShellFamily}. Providers depend on this
 * interface, never on a concrete shell, so shell families stay swappable.
 */
public interface ShellLauncher {

    ShellFamily family();

    /** The argv list running {@code script} (shell executable plus flags). */
    List<String> argv(String script);
}

/** Default launchers: {@code <shell> <flag> <script>} for each family. */
final class FlagShellLauncher implements ShellLauncher {

    private final ShellFamily family;
    private final List<String> prefix;

    FlagShellLauncher(ShellFamily family, List<String> prefix) {
        this.family = family;
        this.prefix = prefix;
    }

    @Override
    public ShellFamily family() {
        return family;
    }

    @Override
    public List<String> argv(String script) {
        List<String> argv = new ArrayList<>(prefix.size() + 1);
        argv.addAll(prefix);
        argv.add(script);
        return argv;
    }
}
