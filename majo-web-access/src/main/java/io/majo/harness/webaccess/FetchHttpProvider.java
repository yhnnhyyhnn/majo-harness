package io.majo.harness.webaccess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anonymous HTTP(S) fetch backend: follows redirects, caps the response body
 * (default 256 KiB — the cap bounds the transfer, not just the buffer),
 * decodes with the response's charset, and converts HTML to readable text —
 * scripts, styles, comments, and hidden content are removed. Non-2xx
 * responses and oversized bodies fail loudly.
 */
public final class FetchHttpProvider implements FetchProvider {

    public static final String PROVIDER_NAME = "http";
    static final int DEFAULT_MAX_BYTES = 256 * 1024;

    private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style|noscript|template)[^>]*>.*?</\\1>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern SPACES = Pattern.compile("[\\t\\r\\n ]+");
    private static final Pattern CHARSET = Pattern.compile(
            "(?i)charset\\s*=\\s*[\"']?([\\w-]+)");

    private final HttpClient client;
    private final int maxBytes;

    public FetchHttpProvider() {
        this(DEFAULT_MAX_BYTES);
    }

    public FetchHttpProvider(int maxBytes) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.maxBytes = maxBytes;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public WebFetchResult fetch(WebFetchRequest request) {
        URI uri = URI.create(request.url());
        try {
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(30))
                            .header("User-Agent", "majo-harness/0.1 (web_fetch)")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                drainQuietly(response.body());
                throw new WebAccessException("web: fetch " + uri + " returned HTTP " + status);
            }
            byte[] body = readBounded(response.body(), uri);
            Charset charset = charsetOf(response.headers().firstValue("Content-Type").orElse(""));
            String html = new String(body, charset);
            return new WebFetchResult(response.uri().toString(), titleOf(html), toText(html));
        } catch (WebAccessException e) {
            throw e;
        } catch (IOException e) {
            throw new WebAccessException("web: cannot fetch " + uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebAccessException("web: cannot fetch " + uri + ": " + e.getMessage(), e);
        }
    }

    /** Reads at most {@code maxBytes} bytes — the cap bounds the transfer, so a huge body fails fast instead of OOMing. */
    private byte[] readBounded(InputStream body, URI uri) throws IOException {
        try (InputStream input = body) {
            byte[] chunk = new byte[8192];
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int read;
            while ((read = input.read(chunk)) != -1) {
                if (buffer.size() + read > maxBytes) {
                    throw new WebAccessException("web: fetch " + uri + " exceeded " + maxBytes + " bytes");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    /** The response charset from Content-Type ({@code null}/unknown = UTF-8). */
    static Charset charsetOf(String contentType) {
        Matcher matcher = CHARSET.matcher(contentType == null ? "" : contentType);
        if (!matcher.find()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(matcher.group(1));
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8; // unknown charset name: fall back
        }
    }

    private static void drainQuietly(InputStream body) {
        try (InputStream stream = body) {
            stream.readAllBytes();
        } catch (IOException ignored) {
            // best-effort connection reclaim
        }
    }

    static String titleOf(String html) {
        java.util.regex.Matcher matcher = TITLE.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        return decode(SPACES.matcher(matcher.group(1)).replaceAll(" ").strip());
    }

    static String toText(String html) {
        String text = COMMENT.matcher(html).replaceAll(" ");
        text = SCRIPT.matcher(text).replaceAll(" ");
        text = TAG.matcher(text).replaceAll(" ");
        text = decode(text);
        return SPACES.matcher(text).replaceAll(" ").strip();
    }

    private static String decode(String input) {
        return input
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                // last: entities the page escaped (e.g. &amp;lt;) must not double-decode
                .replace("&amp;", "&");
    }
}
