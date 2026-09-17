package io.majo.harness.schedule;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import io.jcordis.core.util.Disposable;
import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.session.SessionService;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.util.Disposables;
import java.util.HashMap;
import java.util.Map;

/**
 * Mounts the schedule capability (dsh schedule): the runtime (durable
 * records, restart-safe re-arming) plus the {@code schedule_create}/
 * {@code schedule_list}/{@code schedule_delete} tools. Delivery rides the
 * agent-loop inbox as follow-up turns; without a loop mounted, records still
 * persist but never fire.
 */
public final class SchedulePlugin implements Plugin {

    public static final String NAME = "schedule";

    @Override
    public Object apply(Context ctx, Object config) {
        SessionService sessions = ctx.get(SessionService.NAME);
        AgentLoopService loop = ctx.get(AgentLoopService.NAME);
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        ScheduleService schedules = new ScheduleService(ctx, sessions, loop);
        Disposable create = tools.register(new ScheduleCreateTool(schedules));
        Disposable list = tools.register(new ScheduleListTool(schedules));
        Disposable delete = tools.register(new ScheduleDeleteTool(schedules));
        Disposable tools1 = Disposables.composite(create, list, delete);
        return new Disposable() {
            @Override
            public void dispose() {
                tools1.dispose();
                schedules.close();
            }
        };
    }

    @Override
    public Map<String, Object> inject() {
        Map<String, Object> inject = new HashMap<>();
        inject.put(SessionService.NAME, null);
        inject.put(ToolRegistry.NAME, null);
        return inject;
    }

    @Override
    public String name() {
        return NAME;
    }
}
