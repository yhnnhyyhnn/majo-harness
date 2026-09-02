package io.majo.harness.fs;

import java.util.List;

/**
 * The fs provider seam (Service Definition): implementations execute text
 * reads, text writes, and glob listing against an execution world. The local
 * implementation ships in this module; remote/sandboxed providers implement
 * the same interface so policy and tool consumers never fork.
 *
 * <p>Providers operate on absolute path strings and must throw
 * {@link FsException} for every failure.
 */
public interface FsProvider {

    /** Reads a text file; missing paths or directories fail loudly. */
    String readText(String path);

    /** Writes a text file, creating parent directories as needed. */
    void writeText(String path, String content);

    /** Lists paths under {@code root} matching a glob pattern (absolute, sorted). */
    List<String> glob(String root, String pattern);
}
