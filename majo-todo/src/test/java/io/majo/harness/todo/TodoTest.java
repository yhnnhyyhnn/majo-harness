package io.majo.harness.todo;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.majo.harness.interaction.InteractionContext;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionPlugin;
import io.majo.harness.session.SessionProjections;
import io.majo.harness.session.SessionProjectionsPlugin;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolResult;
import io.majo.harness.tools.ToolsPlugin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * todo_write semantics: whole-list replacement durably recorded as TODO_SET
 * and folded by the {@code todo} projection.
 */
class TodoTest {

    private record Mounted(Context ctx, SessionService sessions, ToolRegistry tools) {
        static Mounted create() {
            Context ctx = Context.create();
            ctx.plugin(new SessionPlugin(), Map.of("store", "memory")).await().join();
            ctx.plugin(new SessionProjectionsPlugin(), null).await().join();
            ctx.plugin(new ToolsPlugin(), null).await().join();
            ctx.plugin(new TodoPlugin(), null).await().join();
            return new Mounted(ctx,
                    ctx.get(SessionService.NAME), ctx.get(ToolRegistry.NAME));
        }
    }

    private static ToolResult write(Mounted h, String sessionId, String arguments) {
        return InteractionContext.runSession(sessionId,
                () -> h.tools().execute(ToolCall.of("todo_write", arguments)));
    }

    @Test
    void writesReplaceTheWholeListAndFoldIntoTheProjection() {
        Mounted h = Mounted.create();
        String sessionId = h.sessions().createSession();
        SessionProjections projections = h.ctx().get(SessionProjections.NAME);
        TodoState todo = projections.require(TodoState.KEY);

        assertThat(todo.items(sessionId)).isEmpty();

        ToolResult first = write(h, sessionId,
                "{\"todos\":[{\"content\":\"read the spec\",\"status\":\"completed\"},"
                        + "{\"content\":\"write the plan\",\"status\":\"in_progress\"},"
                        + "{\"content\":\"implement\",\"status\":\"pending\"}]}");
        assertThat(first.ok()).isTrue();

        List<TodoState.Item> items = todo.items(sessionId);
        assertThat(items).hasSize(3);
        assertThat(items.get(0).content()).isEqualTo("read the spec");
        assertThat(items.get(0).status()).isEqualTo("completed");
        assertThat(items.get(1).status()).isEqualTo("in_progress");

        // second write replaces the list wholesale (not a merge)
        ToolResult second = write(h, sessionId,
                "{\"todos\":[{\"content\":\"only this left\",\"status\":\"pending\"}]}");
        assertThat(second.ok()).isTrue();
        assertThat(todo.items(sessionId)).hasSize(1);
        assertThat(todo.items(sessionId).get(0).content()).isEqualTo("only this left");

        // durable log holds both replacements in order
        List<SessionEventType> kinds = h.sessions().events(sessionId).stream()
                .map(SessionEvent::type).toList();
        assertThat(kinds).containsExactly(SessionEventType.TODO_SET, SessionEventType.TODO_SET);
    }

    @Test
    void invalidStatusAndUnboundSessionFailAsToolErrors() {
        Mounted h = Mounted.create();
        String sessionId = h.sessions().createSession();

        ToolResult bad = write(h, sessionId,
                "{\"todos\":[{\"content\":\"x\",\"status\":\"done\"}]}");
        assertThat(bad.ok()).isFalse();
        assertThat(bad.visibleText()).contains("unknown todo status");

        ToolResult unbound = h.tools().execute(ToolCall.of("todo_write",
                "{\"todos\":[{\"content\":\"x\"}]}")); // no session bound
        assertThat(unbound.ok()).isFalse();
        assertThat(unbound.visibleText()).contains("no session is bound");

        assertThat(h.sessions().events(sessionId)).isEmpty();
    }
}
