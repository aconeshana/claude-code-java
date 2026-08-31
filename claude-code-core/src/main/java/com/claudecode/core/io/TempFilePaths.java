package com.claudecode.core.io;

import com.claudecode.core.util.HashUtils;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Generates (but does not create) temporary paths.
 */
public final class TempFilePaths {
    private TempFilePaths() {}

    public static Path generate(String prefix, String extension) {
        return generate(prefix, extension, null);
    }

    public static Path generate(String prefix, String extension, String contentHash) {
        String actualPrefix = prefix == null ? "claude-prompt" : prefix;
        String actualExtension = extension == null ? ".md" : extension;
        String id = contentHash == null
            ? UUID.randomUUID().toString()
            : HashUtils.hashContent(contentHash).substring(0, 16);
        return Path.of(System.getProperty("java.io.tmpdir"),
            actualPrefix + "-" + id + actualExtension);
    }
}
