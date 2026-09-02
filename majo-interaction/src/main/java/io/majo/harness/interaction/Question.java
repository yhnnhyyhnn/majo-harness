package io.majo.harness.interaction;

import java.util.UUID;

/**
 * An ask-user question: the agent poses {@code text} and awaits a human
 * answer. Ids let queueing handlers correlate submitted answers.
 */
public record Question(String id, String text) {

    public static Question ask(String text) {
        return new Question(UUID.randomUUID().toString(), text);
    }
}
