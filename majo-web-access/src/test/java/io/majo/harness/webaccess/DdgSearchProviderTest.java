package io.majo.harness.webaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure-parse tests for the DuckDuckGo HTML backend (no network). */
final class DdgSearchProviderTest {

    private static final String SAMPLE = """
            <html><body>
              <div class="result results_links results_links_deep web-result">
                <div class="links_main links_deep result__body">
                  <h2 class="result__title">
                    <a rel="nofollow" class="result__a"
                       href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fguide&amp;rut=abc">
                       Example &amp; Guide
                    </a>
                  </h2>
                  <a class="result__snippet" href="//duckduckgo.com/l/?uddg=…">
                    The <b>example</b> guide for majo <i>search</i> demos.
                  </a>
                </div>
              </div>
              <div class="result">
                <h2 class="result__title">
                  <a rel="nofollow" class="result__a" href="https://example.org/faq">FAQ page</a>
                </h2>
                <a class="result__snippet" href="…">Everything about example.org</a>
              </div>
            </body></html>
            """;

    @Test
    void parsesTitleSnippetAndNormalizedUrl() {
        List<WebSearchResult> results = DdgSearchProvider.parse(SAMPLE, 5);
        assertThat(results).hasSize(2);
        WebSearchResult first = results.get(0);
        assertThat(first.title()).isEqualTo("Example & Guide");
        assertThat(first.url()).isEqualTo("https://example.org/guide"); // uddg decoded
        assertThat(first.snippet()).isEqualTo("The example guide for majo search demos.");
        assertThat(results.get(1).title()).isEqualTo("FAQ page");
    }

    @Test
    void respectsLimit() {
        assertThat(DdgSearchProvider.parse(SAMPLE, 1)).hasSize(1);
    }

    @Test
    void emptyHtmlYieldsNoResults() {
        assertThat(DdgSearchProvider.parse("", 5)).isEmpty();
    }
}
