package io.majo.harness.webaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.regex.Pattern;

/**
 * Real no-key search backend: the Wikipedia {@code action=query} search API.
 * The provider is a vendor seam example the way dsh wires exa/perplexity — no
 * credentials, so it stays usable in dev; it is still provider-owned external
 * text and must be labeled untrusted by consumers.
 *
 * <p>Network failure surfaces loudly on {@link #search}; the parse step is
 * separated ({@link #parse}) so offline tests cover response handling.
 */
public final class WikiSearchProvider implements SearchProvider {

    public static final String PROVIDER_NAME = "wiki";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern SPACES = Pattern.compile("[\\t\\r\\n ]+");

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final String language;

    public WikiSearchProvider() {
        this("en");
    }

    public WikiSearchProvider(String language) {
        this.language = language;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public List<WebSearchResult> search(WebSearchRequest request) {
        URI uri = URI.create("https://" + language + ".wikipedia.org/w/api.php"
                + "?action=query&list=search&format=json"
                + "&srlimit=" + request.limit()
                + "&srsearch=" + URLEncoder.encode(request.query(), StandardCharsets.UTF_8));
        try {
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(30))
                            .header("User-Agent", "majo-harness/0.1 (web_search)")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new WebAccessException("web: wiki search returned HTTP " + status);
            }
            return parse(new String(response.body(), StandardCharsets.UTF_8))
                    .stream()
                    .map(result -> language.equals("en")
                            ? result
                            : new WebSearchResult(
                                    result.url().replace("https://en.wikipedia.org/",
                                            "https://" + language + ".wikipedia.org/"),
                                    result.title(), result.snippet()))
                    .toList();
        } catch (WebAccessException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebAccessException("web: cannot reach wiki search for \""
                    + request.query() + "\": " + e.getMessage(), e);
        }
    }

    /** Maps a Wikipedia {@code action=query} JSON body to results. */
    static List<WebSearchResult> parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode hits = root.path("query").path("search");
            List<WebSearchResult> results = new ArrayList<>();
            for (JsonNode hit : hits) {
                String title = hit.path("title").asText("");
                String snippet = SPACES.matcher(TAG.matcher(hit.path("snippet").asText(""))
                        .replaceAll(" ")).replaceAll(" ").strip();
                results.add(new WebSearchResult(
                        "https://en.wikipedia.org/wiki/"
                                + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8),
                        title,
                        snippet));
            }
            return List.copyOf(results);
        } catch (IOException | RuntimeException e) {
            throw new WebAccessException("web: cannot parse wiki search response: " + e.getMessage(), e);
        }
    }
}
