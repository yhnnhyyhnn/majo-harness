package io.majo.harness.interaction;

import java.util.UUID;

/**
 * An ask-user question: the agent poses {@code text} and awaits a human
 * answer. {@code agent} is an optional originating agent label. Ids let
 * queueing handlers correlate submitted answers.
 */
public record Question(String id, String text, String agent) {

    public static Question ask(String text) {
        return new Question(UUID.randomUUID().toString(), text, null);
    }

    public static Question ask(String text, String agent) {
        return new Question(UUID.randomUUID().toString(), text, agent);
    }
}
