package io.majo.harness.web.handler;

import com.sun.net.httpserver.HttpExchange;
import io.jcordis.core.util.Disposable;
import io.majo.harness.agent.loop.AgentLoopService;
import io.majo.harness.interaction.ApprovalRequest;
import io.majo.harness.interaction.Question;
import io.majo.harness.session.SessionEvent;
import io.majo.harness.session.SessionEventType;
import io.majo.harness.session.SessionService;
import io.majo.harness.web.Http;
import io.majo.harness.web.Metrics;
import io.majo.harness.web.PendingInteractions;
import io.majo.harness.web.WebApiModels;
import io.majo.harness.web.WebContext;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Turn execution: the synchronous POST /api/turn and the SSE GET /api/turn/stream. */
public final class TurnHandlers {

    private final WebContext ctx;

    public TurnHandlers(WebContext ctx) {
        this.ctx = ctx;
    }

    public WebApiModels.TurnResult turn(HttpExchange exchange) throws IOException {
        Map<?, ?> request = Http.JSON.readValue(exchange.getRequestBody(), Map.class);
        Object taskValue = request.get("task");
        if (taskValue == null || String.valueOf(taskValue).isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        String task = String.valueOf(taskValue);
        SessionService sessions = ctx.boot.service(SessionService.NAME);
        AgentLoopService loop = ctx.boot.service(AgentLoopService.NAME);
        String sessionId = request.get("sessionId") == null
                ? sessions.createSession()
                : String.valueOf(request.get("sessionId"));
        Object lock = ctx.lockFor(sessionId);
        synchronized (lock) {
            String answer = loop.runTurn(sessionId, task, null,
                    SessionSupport.sessionModelFor(ctx, sessionId));
            return new WebApiModels.TurnResult(sessionId, answer,
                    SessionSupport.eventsJson(sessions.events(sessionId)));
        }
    }

    /**
     * Server-Sent Events turn: durable appends (except the final assistant
     * text, which streams as chunks) relay as {@code log} frames, text tokens
     * as {@code chunk}, completion as {@code done}, failures as {@code fail}.
     */
    public void streamTurn(HttpExchange exchange) throws IOException {
        Map<String, String> query = Http.query(exchange);
        String sessionId = query.get("sessionId");
        String task = query.get("task");
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        String turnId = java.util.UUID.randomUUID().toString();
        exchange.getResponseHeaders().set("X-Turn-Id", turnId);
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        // All SSE writes share one lock so the heartbeat and event frames never
        // interleave mid-line; the heartbeat keeps idle proxy/NAT connections
        // alive while a turn blocks on an approval decision.
        final Object writeLock = new Object();
        AtomicBoolean heartbeatOn = new AtomicBoolean(true);
        AtomicBoolean clientGone = new AtomicBoolean(false);
        try {
            sse(out, writeLock, clientGone, "event: turn\ndata: " + Http.JSON.writeValueAsString(
                    Map.of("turnId", turnId)) + "\n\n");
            if (task == null || task.isBlank() || sessionId == null || sessionId.isBlank()) {
                sse(out, writeLock, clientGone, "event: fail\ndata: " + Http.JSON.writeValueAsString(
                        new WebApiModels.StreamFail("sessionId and task query parameters are required")) + "\n\n");
                Metrics.record(200);
                return;
            }
            Metrics.turn();
            SessionService sessions = ctx.boot.service(SessionService.NAME);
            AgentLoopService loop = ctx.boot.service(AgentLoopService.NAME);
            Thread heartbeat = Thread.ofVirtual().start(() -> {
                while (heartbeatOn.get() && !clientGone.get()) {
                    try {
                        Thread.sleep(10_000);
                        if (heartbeatOn.get()) {
                            try {
                                sse(out, writeLock, clientGone, ": hb\n\n");
                            } catch (IOException gone) {
                                clientGone.set(true); // stream dropped: stop
                            }
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            Disposable listener = ctx.boot.ctx().on(SessionService.EVENT, (thisArg, args) -> {
                String seen = (String) args[0];
                SessionEvent event = (SessionEvent) args[1];
                if (!sessionId.equals(seen)) {
                    return null;
                }
                boolean finalText = event.type() == SessionEventType.ASSISTANT_MESSAGE
                        && event.content() != null
                        && !event.fields().containsKey(SessionEvent.FIELD_TOOL_CALLS);
                if (!finalText) {
                    try {
                        sse(out, writeLock, clientGone, "event: log\ndata: " + Http.JSON.writeValueAsString(
                                SessionSupport.eventsJson(java.util.List.of(event)).get(0)) + "\n\n");
                    } catch (IOException silent) {
                        // listener runs on publisher threads; drop frames quietly
                        clientGone.set(true);
                    }
                }
                return null;
            });
            try {
                Object lock = ctx.lockFor(sessionId);
                synchronized (lock) {
                    PendingInteractions.Notifier streamNotifier = new PendingInteractions.Notifier() {
                        @Override
                        public void approval(ApprovalRequest request) {
                            try {
                                sse(out, writeLock, clientGone, "event: approval\ndata: " + Http.JSON.writeValueAsString(
                                        new WebApiModels.ApprovalFrame(
                                                request.id(), request.summary(), request.details(), request.agent())) + "\n\n");
                            } catch (IOException silent) {
                                clientGone.set(true);
                            }
                        }

                        @Override
                        public void question(Question question) {
                            try {
                                sse(out, writeLock, clientGone, "event: question\ndata: " + Http.JSON.writeValueAsString(
                                        new WebApiModels.QuestionFrame(
                                                question.id(), question.text(), question.agent())) + "\n\n");
                            } catch (IOException silent) {
                                clientGone.set(true);
                            }
                        }
                    };
                    ctx.pending.notifier.set(streamNotifier);
                    try {
                        String answer = loop.runTurn(sessionId, task, delta -> {
                                    try {
                                        sse(out, writeLock, clientGone, "event: chunk\ndata: " + Http.JSON.writeValueAsString(
                                                new WebApiModels.StreamChunk(delta)) + "\n\n");
                                    } catch (IOException silent) {
                                        clientGone.set(true);
                                    }
                                },
                                SessionSupport.sessionModelFor(ctx, sessionId));
                        sse(out, writeLock, clientGone, "event: done\ndata: " + Http.JSON.writeValueAsString(
                                new WebApiModels.StreamDone(sessionId, answer)) + "\n\n");
                        Metrics.record(200);
                    } finally {
                        ctx.pending.notifier.remove();
                    }
                }
            } finally {
                heartbeatOn.set(false);
                listener.dispose();
            }
        } catch (Throwable failure) {
            try {
                sse(out, writeLock, clientGone, "event: fail\ndata: " + Http.JSON.writeValueAsString(
                        new WebApiModels.StreamFail(String.valueOf(failure.getMessage()))) + "\n\n");
            } catch (IOException gone) {
                clientGone.set(true); // nothing left to write; not a server error
            }
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
                clientGone.set(true);
            }
        }
    }

    /** Writes one complete SSE frame under the stream's write lock. */
    private static void sse(OutputStream out, Object writeLock,
            AtomicBoolean clientGone, String frame) throws IOException {
        synchronized (writeLock) {
            if (clientGone.get()) {
                throw new IOException("client aborted");
            }
            out.write(frame.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
