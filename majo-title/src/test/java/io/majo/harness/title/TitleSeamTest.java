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
}
