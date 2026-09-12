package tools.loam.wayloam.router.android;

import android.content.Context;

import java.io.File;

/**
 * App-private storage locations used by the embedded router.
 *
 * Routing data is intentionally kept outside public Downloads so WAYLOAM can validate and manage
 * engine data without depending on shared-storage permissions.
 */
public final class RouterStorage {
    private final File root;
    private final File segments;
    private final File profiles;
    private final File cache;

    private RouterStorage(File root, File segments, File profiles, File cache) {
        this.root = root;
        this.segments = segments;
        this.profiles = profiles;
        this.cache = cache;
    }

    public static RouterStorage create(Context context) {
        File root = new File(context.getNoBackupFilesDir(), "wayloam-router");
        File segments = new File(root, "segments4");
        File profiles = new File(root, "profiles2");
        File cache = new File(root, "cache");

        ensureDirectory(root);
        ensureDirectory(segments);
        ensureDirectory(profiles);
        ensureDirectory(cache);

        return new RouterStorage(root, segments, profiles, cache);
    }

    public File root() {
        return root;
    }

    public File segments() {
        return segments;
    }

    public File profiles() {
        return profiles;
    }

    public File cache() {
        return cache;
    }

    private static void ensureDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Could not create routing directory: " + directory);
        }
    }
}

