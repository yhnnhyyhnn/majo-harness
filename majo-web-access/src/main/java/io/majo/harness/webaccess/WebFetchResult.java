package io.majo.harness.webaccess;

/**
 * A fetched page: the URL, an optional title, and HTML converted to readable
 * text (active/hidden content removed by the provider).
 */
public record WebFetchResult(String url, String title, String text) {}
