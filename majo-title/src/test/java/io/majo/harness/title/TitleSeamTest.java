package io.majo.harness.title;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TitleSeamTest {

    private static SessionService sessions(Context ctx) {
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        return ctx.get(SessionService.NAME);
    }

    @Test
    void heuristicTitlesFromTheFirstUserMessageAndTruncates() {
        Context ctx = Context.create();
        SessionService service = sessions(ctx);
        ctx.plugin(new SessionTitlePlugin(), null).await().join();
        ctx.plugin(new HeuristicTitlePlugin(), null).await().join();
        SessionTitleService titles = ctx.get(SessionTitleService.NAME);
        assertThat(titles.hasProvider()).isTrue();

        String empty = service.createSession();
        assertThat(titles.title(empty)).isNull();

        String titled = service.createSession();
        service.append(titled, SessionEventType.TURN_START, Map.of());
        service.append(titled, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, "  please   summarize  the codebase  "));
        service.append(titled, SessionEventType.ASSISTANT_MESSAGE, Map.of(SessionEvent.FIELD_CONTENT, "done"));
        assertThat(titles.title(titled)).isEqualTo("please summarize the codebase");

        String longOne = service.createSession();
        String text = "x".repeat(200);
        service.append(longOne, SessionEventType.USER_MESSAGE, Map.of(SessionEvent.FIELD_CONTENT, text));
        assertThat(titles.title(longOne)).isEqualTo("x".repeat(57) + "...");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void soleProviderSemanticsAndUnregister() {
        Context ctx = Context.create();
        SessionService service = sessions(ctx);
        ctx.plugin(new SessionTitlePlugin(), null).await().join();
        SessionTitleService titles = ctx.get(SessionTitleService.NAME);
        assertThat(titles.hasProvider()).isFalse();
        assertThatThrownBy(() -> titles.title("anything"))
                .isInstanceOf(TitleException.class)
                .hasMessageContaining("no session title provider");

        Disposable registration = titles.registerProvider(events -> "custom");
        assertThat(titles.hasProvider()).isTrue();
        assertThatThrownBy(() -> titles.registerProvider(events -> "other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");

        String sessionId = service.createSession();
        assertThat(titles.title(sessionId)).isEqualTo("custom");
        registration.dispose();
        assertThat(titles.hasProvider()).isFalse();
        assertThatThrownBy(() -> titles.title(sessionId)).isInstanceOf(TitleException.class);
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void providerClearsWithItsPlugin() {
        Context ctx = Context.create();
        sessions(ctx);
        ctx.plugin(new SessionTitlePlugin(), null).await().join();
        io.jcordis.core.fiber.Fiber provider = ctx.plugin(new HeuristicTitlePlugin(), null).await().join();
        SessionTitleService titles = ctx.get(SessionTitleService.NAME);
        assertThat(titles.hasProvider()).isTrue();

        provider.disposeAsync().join();
        assertThat(titles.hasProvider()).isFalse();
        assertThatThrownBy(() -> titles.title("x")).isInstanceOf(TitleException.class);
        ctx.fiber().disposeAsync().join();
    }

    /**
     * Titles memoize once derived (the sidebar polls every cycle; a
     * derivation parses the whole log), untitled sessions retry until a user
     * message exists, and re-registering a provider forgets the memo.
     */
    @Test
    void titlesMemoizeAndRetryWhileUntitled() {
        Context ctx = Context.create();
        SessionService service = sessions(ctx);
        ctx.plugin(new SessionTitlePlugin(), null).await().join();
        SessionTitleService titles = ctx.get(SessionTitleService.NAME);
        java.util.concurrent.atomic.AtomicInteger derivations =
                new java.util.concurrent.atomic.AtomicInteger();
        Disposable first = titles.registerProvider(events -> {
            derivations.incrementAndGet();
            return events.stream()
                    .anyMatch(event -> event.type() == SessionEventType.USER_MESSAGE)
                    ? "titled"
                    : null;
        });

        String sessionId = service.createSession();
        assertThat(titles.title(sessionId)).isNull();
        assertThat(titles.title(sessionId)).isNull(); // retried while untitled
        service.append(sessionId, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, "hello"));
        assertThat(titles.title(sessionId)).isEqualTo("titled");
        service.append(sessionId, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, "second"));
        assertThat(titles.title(sessionId)).isEqualTo("titled");
        // 2 untitled retries + exactly 1 successful derivation
        assertThat(derivations.get()).isEqualTo(3);

        first.dispose();
        titles.registerProvider(events -> "replacement");
        assertThat(titles.title(sessionId)).isEqualTo("replacement");
        assertThat(derivations.get()).isEqualTo(3); // the old provider ran no more
        ctx.fiber().disposeAsync().join();
    }

    /**
     * The LLM-backed provider: titles trim, memoize after success, and
     * failures (transport errors and empty answers alike) back off so sidebar
     * polling never hammers the model.
     */
    @Test
    void llmProviderTitlesMemoizesAndBacksOffOnFailure() {
        Context ctx = Context.create();
        ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
        ctx.plugin(new io.majo.harness.llm.LLMServicePlugin(),
                Map.of("defaultModel", "model")).await().join();
        ctx.plugin(new SessionTitlePlugin(), null).await().join();
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        io.majo.harness.llm.LLMService llm = ctx.get(io.majo.harness.llm.LLMService.NAME);
        llm.registerModel("model", request -> {
            calls.incrementAndGet();
            String message = request.messages().get(0).content();
            if (message.contains("boom")) {
                throw new io.majo.harness.llm.ModelException("provider down");
            }
            if (message.contains("empty")) {
                return io.majo.harness.llm.ChatResponse.text("   ");
            }
            if (message.contains("verbose")) {
                return io.majo.harness.llm.ChatResponse.text("t".repeat(100));
            }
            return io.majo.harness.llm.ChatResponse.text("  A Great Conversation Title  ");
        });
        ctx.plugin(new LlmTitlePlugin(), Map.of("backoffSeconds", 60)).await().join();
        SessionService sessions = ctx.get(SessionService.NAME);
        SessionTitleService titles = ctx.get(SessionTitleService.NAME);

        String id = sessions.createSession();
        sessions.append(id, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, "help me refactor"));
        assertThat(titles.title(id)).isEqualTo("A Great Conversation Title");
        assertThat(titles.title(id)).isEqualTo("A Great Conversation Title"); // memoized
        assertThat(calls.get()).isEqualTo(1);

        // truncation honors maxChars
        String verbose = sessions.createSession();
        sessions.append(verbose, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, "verbose session please"));
        assertThat(titles.title(verbose)).isEqualTo("t".repeat(57) + "...");

        // failures back off: repeated polls do not hammer the model
        String failing = sessions.createSession();
        sessions.append(failing, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, "boom please"));
        assertThat(titles.title(failing)).isNull();
        assertThat(titles.title(failing)).isNull();
        assertThat(calls.get()).isEqualTo(3); // 2 successes + exactly 1 failure

        String empties = sessions.createSession();
        sessions.append(empties, SessionEventType.USER_MESSAGE,
                Map.of(SessionEvent.FIELD_CONTENT, "empty answer here"));
        assertThat(titles.title(empties)).isNull();
        assertThat(titles.title(empties)).isNull();
        assertThat(calls.get()).isEqualTo(4);
        ctx.fiber().disposeAsync().join();
    }
}
