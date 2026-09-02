package io.majo.harness.headless;

import io.jcordis.core.context.Context;
import io.jcordis.core.logger.ConsoleExporter;
import io.jcordis.loader.EntryOptions;
import io.majo.harness.boot.HarnessBoot;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-shot headless harness: boots the {@code headless.yml} profile with an
 * appended {@code run} entry and prints the resulting session transcript.
 *
 * <pre>
 * mvn -pl majo-headless exec:java -Dexec.args="1+2"
 * </pre>
 */
public final class HeadlessMain {

    public static void main(String[] args) throws IOException {
        String task = args.length > 0 ? args[0] : "1+2";
        String profilePath = args.length > 1 ? args[1] : "headless.yml";

        Context root = Context.create();
        new ConsoleExporter(root);
        HarnessBoot boot = new HarnessBoot(root)
                .register(CalculatorToolPlugin.NAME, new CalculatorToolPlugin())
                .register(RunnerPlugin.NAME, new RunnerPlugin());

        String profileText = loadProfile(profilePath);
        List<EntryOptions> entries = new ArrayList<>(boot.readProfileText(profileText, profilePath));
        entries.add(HarnessBoot.entry(RunnerPlugin.NAME, RunnerPlugin.NAME, Map.of("task", task)));
        boot.launch(entries);

        SessionService sessions = boot.service(SessionService.NAME);
        for (String sessionId : sessions.sessionIds()) {
            System.out.println("== session " + sessionId + " ==");
            String answer = null;
            for (SessionEvent event : sessions.events(sessionId)) {
                System.out.println("  " + event.seq() + " " + event.type() + " " + summarize(event));
                if (event.type() == io.majo.harness.session.SessionEventType.ASSISTANT_MESSAGE
                        && !event.fields().containsKey(SessionEvent.FIELD_TOOL_CALLS)) {
                    answer = event.content();
                }
            }
            System.out.println("answer: " + answer);
        }
        boot.dispose();
    }

    private static String loadProfile(String path) throws IOException {
        try (InputStream stream = HeadlessMain.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("profile not found on classpath: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String summarize(SessionEvent event) {
        if (event.content() != null) {
            return "content=\"" + event.content() + "\"";
        }
        if (event.fields().containsKey(SessionEvent.FIELD_TOOL_CALLS)) {
            return "toolCalls=" + event.fields().get(SessionEvent.FIELD_TOOL_CALLS);
        }
        return event.fields().toString();
    }

    private HeadlessMain() {}
}
