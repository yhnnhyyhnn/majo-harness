package io.majo.harness.webaccess;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Key-less web search backend ({@code web-search-duckduckgo}): queries the
 * DuckDuckGo HTML endpoint and parses the result list. Parsing is pure
 * (offline-testable via {@link #parse}); only the fetch needs the network.
 * Search results are external, untrusted provider text.
 */
public final class DdgSearchProvider implements SearchProvider {

    public static final String PROVIDER_NAME = "duckduckgo";

    private static final Pattern BLOCK = Pattern.compile(
            "class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>(.*?)(?=class=\"result__a\"|$)",
            Pattern.DOTALL);
    private static final Pattern SNIPPET = Pattern.compile("class=\"result__snippet\"[^>]*>(.*?)</a>",
            Pattern.DOTALL);

    private final HttpClient client;
    private final Duration timeout;

    public DdgSearchProvider() {
        this(Duration.ofSeconds(15));
    }

    DdgSearchProvider(Duration timeout) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public List<WebSearchResult> search(WebSearchRequest request) {
        String query = request.query() == null ? "" : request.query().trim();
        if (query.isEmpty()) {
            return List.of();
        }
        URI uri = URI.create("https://html.duckduckgo.com/html/?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        try {
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(timeout)
                            .header("User-Agent", "majo-harness/0.1 (web_search)")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status != 200) {
                throw new WebAccessException("duckduckgo search returned HTTP " + status);
            }
            return parse(new String(response.body(), StandardCharsets.UTF_8), request.limit());
        } catch (WebAccessException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebAccessException("duckduckgo search failed: " + e.getMessage());
        }
    }

    /** Pure parser (offline-testable): extracts DDG result blocks from HTML. */
    static List<WebSearchResult> parse(String html, int limit) {
        List<WebSearchResult> results = new ArrayList<>();
        Matcher blocks = BLOCK.matcher(html);
        while (blocks.find() && results.size() < Math.max(1, limit)) {
            String url = unescape(blocks.group(1));
            String title = strip(blocks.group(2));
            String snippet = "";
            Matcher snippetMatcher = SNIPPET.matcher(blocks.group(3));
            if (snippetMatcher.find()) {
                snippet = strip(snippetMatcher.group(1));
            }
            if (title.isEmpty() || url.isEmpty()) {
                continue;
            }
            // DDG redirects via //duckduckgo.com/l/?uddg=…; normalize those
            int marker = url.indexOf("uddg=");
            if (marker >= 0) {
                String encoded = url.substring(marker + "uddg=".length());
                int end = encoded.indexOf('&');
                if (end >= 0) {
                    encoded = encoded.substring(0, end);
                }
                String decoded = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                if (decoded.startsWith("http")) {
                    url = decoded;
                }
            }
            results.add(new WebSearchResult(url, title, snippet));
        }
        return List.copyOf(results);
    }

    private static String strip(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return unescape(text);
    }

    private static String unescape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }
}
