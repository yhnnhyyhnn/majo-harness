package io.majo.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;

/**
 * Reconnect-aware {@link McpConnection} wrapper (dsh `reconnect` policy
 * analog): lazily reopens the underlying connection when it is dead, with
 * exponential backoff (initial → max delay) and a reconnect attempt budget
 * that resets after the connection stayed stable for a while. Calls that
 * fail because the connection died mid-flight retry once on the fresh
 * connection. Disabled policies fail loudly without retrying.
 */
final class ReconnectingConnection implements McpConnection {

    /** Stability window: a connection up this long resets the attempt budget. */
    static final long STABILITY_WINDOW_MILLIS = 60_000;

    private final String serverName;
    private final Factory factory;
    private final long initialDelayMillis;
    private final long maxDelayMillis;
    private final int maxAttempts;

    private McpConnection current;
    private long stableSince;
    private int budgetUsed;

    ReconnectingConnection(String serverName, McpConnection.Factory factory,
            McpConnection initial, long initialDelayMillis, long maxDelayMillis,
            int maxAttempts) {
        this.serverName = serverName;
        this.factory = factory;
        this.current = initial;
        this.stableSince = System.currentTimeMillis();
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.maxAttempts = maxAttempts;
    }

    /** The live inner connection, reconnecting (bounded) when dead. */
    private synchronized McpConnection inner() {
        if (current != null && current.isAlive()) {
            return current;
        }
        if (current != null) {
            // stability reset: the previous connection lasted long enough
            // that this is a new outage, not a continuation
            if (System.currentTimeMillis() - stableSince >= STABILITY_WINDOW_MILLIS) {
                budgetUsed = 0;
            }
            closeCurrent();
        }
        if (current == null && budgetUsed >= maxAttempts) {
            throw new McpException("server \"" + serverName
                    + "\" reconnect budget exhausted (" + maxAttempts + " attempts)");
        }
        IOException failure = null;
        long delay = initialDelayMillis;
        while (budgetUsed < maxAttempts) {
            budgetUsed++;
            try {
                current = factory.open();
                stableSince = System.currentTimeMillis();
                return current;
            } catch (IOException | RuntimeException e) {
                failure = e instanceof IOException io ? io
                        : new IOException(e.getMessage(), e);
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpException("reconnect to \"" + serverName + "\" interrupted", e);
            }
            delay = Math.min(delay * 2, maxDelayMillis);
        }
        throw new McpException("server \"" + serverName + "\" reconnect failed after "
                + maxAttempts + " attempts: "
                + (failure == null ? "unknown" : failure.getMessage()), failure);
    }

    private synchronized void invalidate() {
        closeCurrent();
    }

    private void closeCurrent() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    @Override
    public List<ToolInfo> listTools() {
        return withConnection(McpConnection::listTools);
    }

    @Override
    public String callTool(String toolName, JsonNode arguments) {
        return withConnection(connection -> connection.callTool(toolName, arguments));
    }

    @Override
    public JsonNode capabilities() {
        return withConnection(McpConnection::capabilities);
    }

    @Override
    public String instructions() {
        return withConnection(McpConnection::instructions);
    }

    @Override
    public JsonNode request(String method, JsonNode params) {
        return withConnection(connection -> connection.request(method, params));
    }

    /**
     * Runs the operation on the live connection; when it died under us
     * mid-call, reconnects once and retries on the fresh connection.
     */
    private synchronized <T> T withConnection(java.util.function.Function<McpConnection, T> operation) {
        McpConnection connection = inner();
        try {
            return operation.apply(connection);
        } catch (McpException e) {
            if (connection.isAlive() || current != connection) {
                throw e;
            }
            closeCurrent();
            return operation.apply(inner());
        }
    }

    @Override
    public boolean isAlive() {
        McpConnection connection = current;
        return connection != null && connection.isAlive();
    }

    @Override
    public synchronized void close() {
        closeCurrent();
        budgetUsed = 0;
    }
}
