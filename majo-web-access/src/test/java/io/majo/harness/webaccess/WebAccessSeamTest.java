package io.majo.harness.webaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.jcordis.core.context.Context;
import io.jcordis.core.util.Disposable;
import io.majo.harness.tools.ToolRegistry;
import io.majo.harness.tools.ToolsPlugin;
import io.majo.harness.tools.ToolCall;
import io.majo.harness.tools.ToolResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WebAccessSeamTest {

    private HttpServer server;

    @AfterEach
    void stop() throws IOException {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startHtmlServer(String html, int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/page", exchange -> {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/page");
            exchange.sendResponseHeaders(301, -1);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static Context stack(Map<String, Object> webConfig) {
        Context ctx = Context.create();
        ctx.plugin(new WebPlugin(), null).await().join();
        if (webConfig != null) {
            ctx.plugin(new StaticSearchPlugin(), webConfig).await().join();
        }
        return ctx;
    }

    @Test
    void fetchProviderConvertsHtmlAndFollowsRedirects() throws IOException {
        String base = startHtmlServer("""
                <html><head><title>Hello &amp; welcome</title></head>
                <body><script>var x=1;</script><style>p{}</style>
                <h1>Title</h1><p>Some  text   here.</p></body></html>""", 200);
        Context ctx = Context.create();
        ctx.plugin(new WebPlugin(), null).await().join();
        ctx.plugin(new FetchHttpPlugin(), null).await().join();
        WebAccessService web = ctx.get(WebAccessService.NAME);

        WebFetchResult fetched = web.fetch(new WebFetchRequest(base + "/redirect"));
        assertThat(fetched.url()).endsWith("/page");
        assertThat(fetched.title()).isEqualTo("Hello & welcome");
        assertThat(fetched.text()).contains("Title").contains("Some text here.").doesNotContain("script");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void fetchFailuresAndMissingProviderAreStructured() throws IOException {
        String base = startHtmlServer("oops", 404);
        Context ctx = Context.create();
        ctx.plugin(new WebPlugin(), null).await().join();
        // no fetch provider mounted
        WebAccessService web = ctx.get(WebAccessService.NAME);
        assertThatThrownBy(() -> web.fetch(new WebFetchRequest("https://example.org/x")))
                .isInstanceOf(WebAccessException.class)
                .hasMessageContaining("no fetch provider");

        ctx.plugin(new FetchHttpPlugin(), null).await().join();
        assertThatThrownBy(() -> web.fetch(new WebFetchRequest(base + "/page")))
                .isInstanceOf(WebAccessException.class)
                .hasMessageContaining("HTTP 404");
        assertThatThrownBy(() -> web.fetch(new WebFetchRequest(base + "/page"), "nope"))
                .isInstanceOf(WebAccessException.class)
                .hasMessageContaining("nope");
        ctx.fiber().disposeAsync().join();
    }

    @Test
    void searchUsesStaticProviderAndFailsWithoutOne() {
        Context withSearch = stack(Map.of("results", List.of(Map.of(
                "url", "https://example.org/faq",
                "title", "Example FAQ",
                "snippet", "Everything about example.org"))));
        WebAccessService web = withSearch.get(WebAccessService.NAME);
        List<WebSearchResult> hits = web.search(WebSearchRequest.of("faq"));
        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.url()).isEqualTo("https://example.org/faq");
            assertThat(hit.title()).isEqualTo("Example FAQ");
        });
        assertThat(web.search(WebSearchRequest.of("nothing-matches-xyz"))).isEmpty();
        withSearch.fiber().disposeAsync().join();

        Context none = Context.create();
        none.plugin(new WebPlugin(), null).await().join();
        WebAccessService bare = none.get(WebAccessService.NAME);
        assertThatThrownBy(() -> bare.search(WebSearchRequest.of("x")))
                .isInstanceOf(WebAccessException.class)
                .hasMessageContaining("no search provider");
        Disposable registration = bare.registerSearchProvider(
                new StaticSearchProvider(List.of(new WebSearchResult("u", "t", "s"))));
        assertThat(bare.search(WebSearchRequest.of("t"))).hasSize(1);
        registration.dispose();
        assertThatThrownBy(() -> bare.search(WebSearchRequest.of("x")))
                .isInstanceOf(WebAccessException.class);
        none.fiber().disposeAsync().join();
    }

    @Test
    void toolsExposeStructuredErrorsAndResults() throws IOException {
        Context ctx = Context.create();
        ctx.plugin(new ToolsPlugin(), null).await().join();
        ctx.plugin(new WebPlugin(), null).await().join();
        ctx.plugin(new WebToolsPlugin(), null).await().join();
        ToolRegistry tools = ctx.get(ToolRegistry.NAME);
        assertThat(tools.specs()).extracting(spec -> spec.name()).containsExactly("web_search", "web_fetch");

        // tools visible even with no provider mounted: structured error
        ToolResult searchError = tools.execute(ToolCall.of("web_search", "{\"query\":\"x\"}"));
        assertThat(searchError.ok()).isFalse();
        assertThat(searchError.visibleText()).contains("no search provider");

        ctx.plugin(new StaticSearchPlugin(), Map.of("results", List.of(Map.of(
                "url", "https://example.org/faq", "title", "FAQ", "snippet", "about")))).await().join();
        ToolResult searchOk = tools.execute(ToolCall.of("web_search", "{\"query\":\"FAQ\"}"));
        assertThat(searchOk.ok()).isTrue();
        assertThat(searchOk.content()).contains("external web results (untrusted)").contains("example.org/faq");

        String base = startHtmlServer("<html><title>Page</title><p>hello web</p></html>", 200);
        ctx.plugin(new FetchHttpPlugin(), null).await().join();
        ToolResult fetchOk = tools.execute(ToolCall.of("web_fetch",
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                        Map.of("url", base + "/page"))));
        assertThat(fetchOk.ok()).isTrue();
        assertThat(fetchOk.content()).contains("hello web");

        ToolResult badArgs = tools.execute(ToolCall.of("web_fetch", "{}"));
        assertThat(badArgs.ok()).isFalse();
        ctx.fiber().disposeAsync().join();
    }
}
