package io.majo.harness.webaccess;

/**
 * One search hit. Provider-controlled text is always labeled external and
 * untrusted by consumers — never treated as harness output.
 */
public record WebSearchResult(String url, String title, String snippet) {}
