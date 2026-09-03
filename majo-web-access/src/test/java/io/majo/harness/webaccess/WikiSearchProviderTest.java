package io.majo.harness.webaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class WikiSearchProviderTest {

    @Test
    void parseMapsWikipediaQueryResults() {
        String json = """
                {
                  "query": {
                    "search": [
                      {"title": "Majo (plant)", "snippet": "<span class=\\"searchmatch\\">Majo</span> is a genus of flowering plants"},
                      {"title": "Agent harness", "snippet": "An <i>agent harness</i> orchestrates tools"}
                    ]
                  }
                }""";
        List<WebSearchResult> results = WikiSearchProvider.parse(json);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).title()).isEqualTo("Majo (plant)");
        assertThat(results.get(0).snippet()).isEqualTo("Majo is a genus of flowering plants");
        assertThat(results.get(0).url()).isEqualTo(
                "https://en.wikipedia.org/wiki/Majo_%28plant%29");
        assertThat(results.get(1).url()).isEqualTo(
                "https://en.wikipedia.org/wiki/Agent_harness");
    }

    @Test
    void parseSurvivesEmptyAndFailsLoudOnGarbage() {
        assertThat(WikiSearchProvider.parse("{}")).isEmpty();
        assertThat(WikiSearchProvider.parse("{\"query\":{\"search\":[]}}")).isEmpty();
        assertThatThrownBy(() -> WikiSearchProvider.parse("not json"))
                .isInstanceOf(WebAccessException.class)
                .hasMessageContaining("parse");
    }
}
