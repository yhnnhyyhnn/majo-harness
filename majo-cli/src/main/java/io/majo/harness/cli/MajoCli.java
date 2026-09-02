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
import java.io.PrintStream;
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
 * majo "task"                                   # one-shot run, built-in headless profile
 * majo --profile ./my-profile.yml "task"        # any file of builtin-name rows
 * majo --plugin ext=./ext-plugin.jar "task"     # mount an external plugin jar
 * majo --profiles                               # list built-in profiles
 * </pre>
 *
 * <p>Exit codes: 0 on success, 1 on a failed run, 2 on usage errors.
 */
public final class MajoCli {

    static final String BUILTIN_HEADLESS = "headless";
    private static final String BUILTIN_PROFILE_RESOURCE = "headless.yml";

    private final List<String> args;
    private final PrintStream out;
    private final PrintStream err;

    public static void main(String[] args) {
        System.exit(new MajoCli(List.of(args), System.out, System.err).run());
    }

    MajoCli(List<String> args, PrintStream out, PrintStream err) {
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
            if (options.task == null) {
                printUsage(err);
                return 2;
            }
            runOnce(options.task, options.profile, options.plugins);
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

    /** One-shot headless run (prints the transcript of the appended run row). */
    private void runOnce(String task, String profile, List<String> plugins) throws IOException, UsageException {
        HarnessBoot boot = boot(profile, plugins);
        try {
            String profileText = profileText(profile);
            List<EntryOptions> entries = new ArrayList<>(boot.readProfileText(profileText, hintOf(profile)));
            if (entries.stream().noneMatch(entry -> RunnerPlugin.NAME.equals(entry.id))) {
                entries.add(HarnessBoot.entry(RunnerPlugin.NAME, RunnerPlugin.NAME, Map.of("task", task)));
            }
            boot.launch(entries);
            SessionService sessions = boot.service(SessionService.NAME);
            TranscriptPrinter.print(sessions, out);
        } finally {
            boot.dispose();
        }
    }

    private HarnessBoot boot(String profile, List<String> plugins) throws IOException, UsageException {
        Context root = Context.create();
        new ConsoleExporter(root);
        HarnessBoot boot = new HarnessBoot(root)
                .register(CalculatorToolPlugin.NAME, new CalculatorToolPlugin())
                .register(RunnerPlugin.NAME, new RunnerPlugin());
        for (String plugin : plugins) {
            int equals = plugin.indexOf('=');
            if (equals <= 0) {
                throw new UsageException("--plugin expects name=path, got \"" + plugin + "\"");
            }
            boot.loadPluginJar(Path.of(plugin.substring(equals + 1)), plugin.substring(0, equals));
        }
        return boot;
    }

    private static String profileText(String profile) throws IOException {
        if (BUILTIN_HEADLESS.equals(profile)) {
            try (InputStream stream = MajoCli.class.getClassLoader().getResourceAsStream(BUILTIN_PROFILE_RESOURCE)) {
                if (stream == null) {
                    throw new IOException("built-in profile missing from classpath: " + BUILTIN_PROFILE_RESOURCE);
                }
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return Files.readString(Path.of(profile));
    }

    private static String hintOf(String profile) {
        return BUILTIN_HEADLESS.equals(profile) ? BUILTIN_HEADLESS + ".yml" : profile;
    }

    private static void printUsage(PrintStream target) {
        target.println("""
                usage: majo [options] "task"

                options:
                  -p, --profile <name|path>   profile to boot; built-in: headless (default)
                  -P, --plugin <name=path>    mount an external plugin jar (repeatable)
                  --profiles                  list built-in profiles and exit
                  -h, --help                  show this help

                examples:
                  majo "1+2"                                 # one-shot (built-in profile)
                  majo --profile ./my-profile.yml "task"     # custom profile of builtin rows
                  majo --plugin ext=./ext.jar "task"         # jar plugins + task
                """);
    }

    /** Parsed CLI options. */
    static final class Options {
        boolean help;
        boolean listProfiles;
        String profile = BUILTIN_HEADLESS;
        String task;
        List<String> plugins = new ArrayList<>();

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
                    case "--plugin", "-P" -> {
                        if (i + 1 >= args.size()) {
                            throw new UsageException("missing value for " + arg);
                        }
                        options.plugins.add(args.get(++i));
                    }
                    default -> {
                        if (arg.startsWith("--") || arg.startsWith("-p") || arg.startsWith("-P")) {
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
