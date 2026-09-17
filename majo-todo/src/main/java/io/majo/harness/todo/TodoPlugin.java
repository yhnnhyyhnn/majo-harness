package io.majo.harness.todo;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.session.SessionProjections;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.util.Disposables;
import java.util.HashMap;
import java.util.Map;

/**
 * Mounts the todo capability (dsh todo): registers the {@code todo} projection
 * unit (fold of TODO_SET events) and the {@code todo_write} tool. Unloading
 * reverts both.
 */
public final class TodoPlugin implements Plugin {

    public static final String NAME = "todo";

    @Override
    public Object apply(Context ctx, Object config) {
        SessionService sessions = ctx.get(SessionService.NAME);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        SessionProjections projections = ctx.get(SessionProjections.NAME);
        Disposable projection = projections.register(TodoState.KEY, new TodoState());
        Disposable tool = tools.register(new TodoWriteTool(sessions));
        return Disposables.composite(projection, tool);
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(SessionService.NAME, null);
        inject.put(ToolRegistry.NAME, null);
        inject.put(SessionProjections.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
