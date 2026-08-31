package com.claudecode.sdk;

import com.claudecode.core.util.HashUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;

/** Portable SDK SessionStore project-key derivation. */
final class SessionStoreProjectKey {
    private static final int MAX_LENGTH = 200;
    private SessionStoreProjectKey() {}

    static String fromDirectory(String directory) {
        Path path = Path.of(directory == null ? "." : directory).toAbsolutePath().normalize();
        try { path = path.toRealPath(); } catch (IOException _) { }
        String canonical = Normalizer.normalize(path.toString(), Normalizer.Form.NFC);
        String sanitized = canonical.replaceAll("[^A-Za-z0-9]", "-");
        if (sanitized.length() <= MAX_LENGTH) return sanitized;
        String hash = Long.toString(Math.abs((long) HashUtils.djb2(canonical)), 36);
        return sanitized.substring(0, MAX_LENGTH) + "-" + hash;
    }
}
