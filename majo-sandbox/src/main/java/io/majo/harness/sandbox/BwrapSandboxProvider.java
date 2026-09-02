package io.majo.harness.sandbox;

import java.util.ArrayList;
import java.util.List;

/**
 * Linux bubblewrap confinement (a {@link SandboxProvider} Strategy): wraps argv
 * as {@code bwrap <options> -- <argv>}. Options are pure configuration
 * (namespace setup, root, binds); this class never executes bwrap itself — a
 * spawner consumer applies {@link #confine} and runs the result. On platforms
 * without bwrap, configuration fails loudly rather than pretending to confine.
 */
public final class BwrapSandboxProvider implements SandboxProvider {

    public static final String PROVIDER_NAME = "bwrap";
    private static final String DEFAULT_EXECUTABLE = "bwrap";

    private final String executable;
    private final List<String> options;

    public BwrapSandboxProvider(List<String> options) {
        this(DEFAULT_EXECUTABLE, options);
    }

    public BwrapSandboxProvider(String executable, List<String> options) {
        if (executable == null || executable.isBlank()) {
            throw new SandboxException("bwrap sandbox: executable must not be blank");
        }
        if (options == null || options.isEmpty()) {
            throw new SandboxException("bwrap sandbox: confinement options must not be empty");
        }
        this.executable = executable;
        this.options = List.copyOf(options);
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public List<String> confine(List<String> argv) {
        if (argv.isEmpty()) {
            throw new SandboxException("bwrap sandbox: cannot confine an empty argv");
        }
        List<String> confined = new ArrayList<>(options.size() + argv.size() + 2);
        confined.add(executable);
        confined.addAll(options);
        confined.add("--");
        confined.addAll(argv);
        return List.copyOf(confined);
    }
}
