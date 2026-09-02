package io.majo.harness.fs;

/**
 * The fs extension points: waterfall events dispatched around each operation.
 *
 * <p>Listeners run before the provider and may rewrite an argument (mutate
 * {@code args[i]} and call {@code next()}) or reject the operation outright by
 * throwing an {@link FsException} without calling {@code next()} — the
 * rejection surfaces to the caller as that same loud failure. This is where
 * path policy, approval, and observability plugins attach without importing
 * the service.
 */
public final class FsEvents {

    /** Waterfall {@code (String path)} before a text read. */
    public static final String READ = "fs/read";
    /** Waterfall {@code (String path, String content)} before a text write. */
    public static final String WRITE = "fs/write";
    /** Waterfall {@code (String root, String pattern)} before a glob. */
    public static final String GLOB = "fs/glob";

    private FsEvents() {}
}
