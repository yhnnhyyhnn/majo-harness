package io.majo.harness.cli;

import io.jcordis.core.context.Context;
import io.jcordis.core.logger.ConsoleExporter;
import io.jcordis.loader.EntryOptions;
import io.majo.harness.boot.HarnessBoot;
import io.majo.harness.headless.CalculatorToolPlugin;
import io.majo.harness.headless.RunnerPlugin;
import io.majo.harness.headless.TranscriptPrinter;
import io.majo.harness.session.SessionService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The majo CLI — a dsh-style launcher:
 *
 * <pre>
 * majo "task"                                   # built-in headless profile (mock model)
 * majo --profile headless "task"                # same, explicit
 * majo --profile ./my-profile.yml "task"        # any file of builtin-name rows
 * majo --profiles                               # list built-in profiles
 * </pre>
 *
 * <p>Exit codes: 0 on success, 1 on a failed run, 2 on usage errors. The task
 * text is required (use the mock model or point a provider at your own
 * endpoint — see the README).
 */
public final class MajoCli {

    static final String BUILTIN_HEADLESS = "headless";
    private static final String BUILTIN_PROFILE_RESOURCE = "headless.yml";

    private final List<String> args;
    private final java.io.PrintStream out;
    private final java.io.PrintStream err;

    public static void main(String[] args) {
        System.exit(new MajoCli(List.of(args), System.out, System.err).run());
    }

    MajoCli(List<String> args, java.io.PrintStream out, java.io.PrintStream err) {
        this.args = args;
        this.out = out;
        this.err = err;
    }

    int run() {
        try {
            Options options = Options.parse(args);
            if (options.help) {
                printUsage(out);
                return 0;
            }
            if (options.listProfiles) {
                out.println("built-in profiles: " + BUILTIN_HEADLESS);
                out.println("(any --profile path to a YAML row list is also accepted)");
                return 0;
            }
            String task = options.task;
            if (task == null) {
                printUsage(err);
                return 2;
            }
            execute(task, options.profile);
            return 0;
        } catch (UsageException e) {
            err.println("majo: " + e.getMessage());
            printUsage(err);
            return 2;
        } catch (Throwable failure) {
            err.println("majo: run failed:");
            failure.printStackTrace(err);
            return 1;
        }
    }

    /** Boots the profile, runs the task, prints the transcript, disposes. */
    private void execute(String task, String profile) throws IOException {
        String profileText;
        String hint;
        if (profile.equals(BUILTIN_HEADLESS)) {
            hint = BUILTIN_HEADLESS + ".yml";
            profileText = classpathProfile(BUILTIN_PROFILE_RESOURCE);
        } else {
            Path file = Path.of(profile);
            hint = profile;
            profileText = Files.readString(file);
        }

        Context root = Context.create();
        new ConsoleExporter(root);
        HarnessBoot boot = new HarnessBoot(root)
                .register(CalculatorToolPlugin.NAME, new CalculatorToolPlugin())
                .register(RunnerPlugin.NAME, new RunnerPlugin());

        List<EntryOptions> entries = new ArrayList<>(boot.readProfileText(profileText, hint));
        if (entries.stream().noneMatch(entry -> RunnerPlugin.NAME.equals(entry.id))) {
            entries.add(HarnessBoot.entry(RunnerPlugin.NAME, RunnerPlugin.NAME, Map.of("task", task)));
        }
        boot.launch(entries);

        SessionService sessions = boot.service(SessionService.NAME);
        TranscriptPrinter.print(sessions, out);
        boot.dispose();
    }

    private static String classpathProfile(String resource) throws IOException {
        try (InputStream stream = MajoCli.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("built-in profile missing from classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void printUsage(java.io.PrintStream target) {
        target.println("""
                usage: majo [options] "task"

                options:
                  --profile <name|path>   profile to boot; built-in: headless (default)
                  --profiles              list built-in profiles and exit
                  -h, --help              show this help

                examples:
                  majo "1+2"                              # built-in headless profile
                  majo --profile ./my-profile.yml "task"  # custom profile of builtin rows
                """);
    }

    /** Parsed CLI options; positional text is the task. */
    static final class Options {
        boolean help;
        boolean listProfiles;
        String profile = BUILTIN_HEADLESS;
        String task;

        static Options parse(List<String> args) {
            Options options = new Options();
            List<String> positional = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                switch (arg) {
                    case "-h", "--help" -> options.help = true;
                    case "--profiles" -> options.listProfiles = true;
                    case "--profile", "-p" -> {
                        if (i + 1 >= args.size()) {
                            throw new UsageException("missing value for " + arg);
                        }
                        options.profile = args.get(++i);
                    }
                    default -> {
                        if (arg.startsWith("--") || arg.startsWith("-p")) {
                            throw new UsageException("unknown option: " + arg);
                        }
                        positional.add(arg);
                    }
                }
            }
            options.task = positional.isEmpty() ? null : String.join(" ", positional);
            return options;
        }
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
