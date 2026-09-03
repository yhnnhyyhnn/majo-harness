package io.majo.harness.webaccess;

/**
 * A fetch backend (Strategy): fetches one URL as readable text. The shipped
 * {@link FetchHttpProvider} is anonymous HTTP(S); remote/rendered backends
 * implement the same interface.
 */
public interface FetchProvider {

    String name();

    WebFetchResult fetch(WebFetchRequest request);
}
