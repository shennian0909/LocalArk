package com.codex.arklocal;

import java.io.File;
import java.io.IOException;

/** App-private backend root. Never mutates the process-wide user.dir property. */
public final class BackendPaths {
    private static volatile File directory;
    private BackendPaths() { }
    public static void setRoot(File root) {
        if (root == null) throw new IllegalArgumentException("root is required");
        final File canonical;
        try { canonical = root.getCanonicalFile(); }
        catch (IOException ex) { throw new IllegalArgumentException("Cannot resolve backend root", ex); }
        if (!canonical.isDirectory()) throw new IllegalArgumentException("Backend root must be an existing directory");
        directory = canonical;
    }
    public static void setRoot(String root) { setRoot(root == null ? null : new File(root)); }
    public static File rootFile() {
        File root = directory;
        if (root == null) throw new IllegalStateException("Backend root is not initialized");
        return root;
    }
    public static String root() { return rootFile().getAbsolutePath(); }
}
