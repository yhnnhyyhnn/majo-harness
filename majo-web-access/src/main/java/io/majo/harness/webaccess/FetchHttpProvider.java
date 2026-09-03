package io.majo.harness.webaccess;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Anonymous HTTP(S) fetch backend: follows redirects, caps the response body
 * (default 256 KiB), and converts HTML to readable text — scripts, styles,
 * comments, and hidden content are removed. Non-2xx responses and oversized
 * bodies fail loudly.
 */
public final class FetchHttpProvider implements FetchProvider {

    public static final String PROVIDER_NAME = "http";
    static final int DEFAULT_MAX_BYTES = 256 * 1024;

    private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style|noscript|template)[^>]*>.*?</\\1>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern SPACES = Pattern.compile("[\\t\\r\\n ]+");

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
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(30))
                            .header("User-Agent", "majo-harness/0.1 (web_fetch)")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new WebAccessException("web: fetch " + uri + " returned HTTP " + status);
            }
            byte[] body = response.body();
            if (body.length > maxBytes) {
                throw new WebAccessException("web: fetch " + uri + " exceeded " + maxBytes + " bytes");
            }
            String html = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            return new WebFetchResult(response.uri().toString(), titleOf(html), toText(html));
        } catch (WebAccessException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebAccessException("web: cannot fetch " + uri + ": " + e.getMessage(), e);
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
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }
}
