package com.claudecode.core.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CwdStateTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesSymlinkAliasesToOneCanonicalProjectIdentity() throws IOException {
        Path real = Files.createDirectory(tempDir.resolve("real-project"));
        Path alias = tempDir.resolve("project-alias");
        try {
            Files.createSymbolicLink(alias, real);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "symbolic links unavailable: " + e.getMessage());
        }

        assertEquals(real.toRealPath(), CwdState.canonicalizeStartupCwd(alias));
    }

    @Test
    void fallsBackToNormalizedAbsolutePathWhenRealpathIsUnavailable() {
        Path missing = tempDir.resolve("missing").resolve("..").resolve("future-project");
        assertEquals(tempDir.resolve("future-project").toAbsolutePath().normalize(),
            CwdState.canonicalizeStartupCwd(missing));
    }
}
