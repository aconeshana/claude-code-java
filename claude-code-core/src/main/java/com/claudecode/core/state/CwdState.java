package com.claudecode.core.state;

import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;


public final class CwdState {

    private static volatile Path originalCwd;

    private CwdState() {}

    /**
     * NFC-normalized,
     * absolute, set at startup and on worktree transitions.
     */
    public static void setOriginalCwd(Path cwd) {
        if (cwd == null) {
            originalCwd = null;
            return;
        }
        originalCwd = Path.of(Normalizer.normalize(
            cwd.toAbsolutePath().normalize().toString(), Normalizer.Form.NFC));
    }

    /**
     * Resolves the startup cwd through realpath when possible, falling back to the normalized absolute
     * input when the filesystem rejects canonicalization.
     */
    public static Path canonicalizeStartupCwd(Path cwd) {
        Path normalized = cwd.toAbsolutePath().normalize();
        try {
            normalized = normalized.toRealPath();
        } catch (IOException | SecurityException _) {

        }
        return Path.of(Normalizer.normalize(normalized.toString(), Normalizer.Form.NFC));
    }


    public static Path getOriginalCwd() {
        return originalCwd;
    }

    /** Test isolation only. */
    public static void clearForTesting() {
        originalCwd = null;
    }
}
