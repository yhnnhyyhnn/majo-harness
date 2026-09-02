package io.majo.harness.fs;

import io.jcordis.core.context.Context;
import io.jcordis.core.service.Service;
import java.util.List;

/**
 * The filesystem service ({@code ctx.fs}): delegates to a {@link FsProvider}
 * behind the {@link FsEvents fs/*} waterfalls, so policy and observability
 * plugins participate in every operation. Providers throw {@link FsException}
 * for failures; listeners may throw it to reject before execution.
 */
public final class FileSystemService extends Service {

    public static final String NAME = "fs";

    private final FsProvider provider;

    public FileSystemService(Context ctx) {
        this(ctx, new LocalFsProvider());
    }

    public FileSystemService(Context ctx, FsProvider provider) {
        super(ctx, NAME);
        this.provider = provider;
    }

    /** Reads a text file through the {@code fs/read} waterfall. */
    public String readText(String path) {
        return (String) ctx.waterfall(null, FsEvents.READ, new Object[] {path},
                args -> provider.readText((String) args[0]));
    }

    /** Writes a text file through the {@code fs/write} waterfall. */
    public void writeText(String path, String content) {
        ctx.waterfall(null, FsEvents.WRITE, new Object[] {path, content},
                args -> {
                    provider.writeText((String) args[0], (String) args[1]);
                    return null;
                });
    }

    /** Globs under {@code root} through the {@code fs/glob} waterfall. */
    public List<String> glob(String root, String pattern) {
        @SuppressWarnings("unchecked")
        List<String> matches = (List<String>) ctx.waterfall(null, FsEvents.GLOB,
                new Object[] {root, pattern},
                args -> provider.glob((String) args[0], (String) args[1]));
        return matches;
    }
}
