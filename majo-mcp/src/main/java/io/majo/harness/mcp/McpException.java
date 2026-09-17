package io.majo.harness.mcp;

/** Signals an MCP transport or protocol failure. */
public final class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
