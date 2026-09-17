package io.majo.harness.llm.fault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scripted-failure HTTP server for wire-level LLM tests (the dsh
 * {@code llm-mock-server} analog). Built on a raw {@link ServerSocket} so a
 * script controls the exact bytes on the wire — including lying about
 * {@code Content-Length} and slamming the connection shut mid-body, which a
 * regular HTTP server stack refuses to do.
 *
 * <p>Each scripted {@link Response} is consumed by one request, in order;
 * requests beyond the script get {@code 500}. Scripts cover: HTTP error
 * statuses (429/5xx with arbitrary headers), well-formed bodies, malformed
 * SSE payloads, and mid-body disconnects.
 */
public final class FaultLlmServer implements AutoCloseable {

    /** One scripted response: status, headers, raw body bytes, framing. */
    public static final class Response {

        final int status;
        final Map<String, String> headers;
        final byte[] body;
        /** When set, the declared Content-Length exceeds the body by this many bytes. */
        final int lieAboutLengthBy;

        private Response(int status, Map<String, String> headers, byte[] body, int lieAboutLengthBy) {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.lieAboutLengthBy = lieAboutLengthBy;
        }
    }

    private final ServerSocket socket;
    private final List<Response> script = new ArrayList<>();
    private final Thread acceptor;
    private volatile boolean running = true;
    private int served;

    private FaultLlmServer(ServerSocket socket) throws IOException {
        this.socket = socket;
        this.acceptor = Thread.ofVirtual().name("fault-llm-server").start(this::acceptLoop);
    }

    /** Binds a fresh server on an ephemeral local port. */
    public static FaultLlmServer start() throws IOException {
        return new FaultLlmServer(new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()));
    }

    /** Base URL requests should target. */
    public String baseUrl() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    /** Requests served so far (including over-script defaults). */
    public synchronized int served() {
        return served;
    }

    /** Appends scripted responses; consumed in request order. */
    public FaultLlmServer enqueue(Response... responses) {
        synchronized (script) {
            script.addAll(List.of(responses));
        }
        return this;
    }

    /** A response with {@code status}, {@code headers}, and a UTF-8 {@code body}. */
    public static Response response(int status, Map<String, String> headers, String body) {
        return new Response(status, headers, body.getBytes(StandardCharsets.UTF_8), 0);
    }

    /**
     * A mid-body disconnect: declares {@code body.length() + promiseExtra}
     * bytes of Content-Length, writes only {@code body}, then closes the
     * connection — the client must see a truncated stream, not a clean EOF.
     */
    public static Response truncatedBody(String body, int promiseExtra) {
        return new Response(200, Map.of(), body.getBytes(StandardCharsets.UTF_8), promiseExtra);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket connection = socket.accept();
                Thread.ofVirtual().start(() -> serve(connection));
            } catch (IOException e) {
                if (running) {
                    // bind-side failure while live: stop serving rather than spin
                    running = false;
                }
                return;
            }
        }
    }

    private void serve(Socket connection) {
        try (connection) {
            HttpRequest request = readRequest(connection.getInputStream());
            Response response = next();
            writeResponse(connection, response);
        } catch (IOException e) {
            // a test-triggered abrupt close lands here; nothing to do
        }
    }

    private record HttpRequest(int contentLength) {
    }

    /** Reads headers (then drains the body); only Content-Length matters. */
    private static HttpRequest readRequest(InputStream in) throws IOException {
        int contentLength = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isBlank()) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        while (contentLength > 0) {
            long skipped = in.skip(contentLength);
            if (skipped <= 0) {
                break;
            }
            contentLength -= skipped;
        }
        return new HttpRequest(contentLength);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return line.toString().replaceAll("\r$", "");
            }
            line.append((char) c);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private synchronized Response next() {
        served++;
        synchronized (script) {
            if (!script.isEmpty()) {
                return script.remove(0);
            }
        }
        return response(500, Map.of(), "fault-llm-server: script exhausted");
    }

    private static void writeResponse(Socket connection, Response response)
            throws IOException {
        OutputStream out = connection.getOutputStream();
        int declared = response.body.length + response.lieAboutLengthBy;
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(response.status).append(" x\r\n")
                .append("Content-Length: ").append(declared).append("\r\n")
                .append("Connection: close\r\n");
        response.headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(response.body);
        out.flush();
        // try-with-resources close() follows: an orderly FIN after fewer body
        // bytes than Content-Length promised — the client sees a truncated
        // body and must fail loudly, never mistake it for a clean EOF
    }

    @Override
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException e) {
            // closing a test server: nothing to recover
        }
        acceptor.interrupt();
    }
}
