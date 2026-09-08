package io.majo.harness.webaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Live network probe for the DuckDuckGo backend. Skipped unless
 * {@code MAJO_NET_PROBE=1}; CI runs it as a best-effort step
 * (continue-on-error) so the default build stays offline-safe while real
 * queries still get exercised on runners that allow outbound traffic.
 */
@EnabledIfEnvironmentVariable(named = "MAJO_NET_PROBE", matches = "1")
final class DdgLiveProbeTest {

    @Test
    void liveDuckDuckGoSearchReturnsParsableResults() {
        DdgSearchProvider provider = new DdgSearchProvider();
        List<WebSearchResult> results = provider.search(WebSearchRequest.of("majo harness"));
        System.out.println("[ddg-live] returned " + results.size() + " results");
        assertThat(results).as("live DuckDuckGo results").isNotEmpty();
        for (WebSearchResult result : results) {
            System.out.println("[ddg-live] " + result.title() + " | " + result.url() + " | " + result.snippet());
            assertThat(result.title()).isNotBlank();
            assertThat(result.url()).startsWith("http");
        }
    }
}
