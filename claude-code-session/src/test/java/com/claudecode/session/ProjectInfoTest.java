package com.claudecode.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ProjectInfo#nameOf} — display name derivation (Wake's last-segment rule). */
class ProjectInfoTest {

    @Test
    void nameOfUsesLastPathSegment() {
        assertEquals("claude-code-java", ProjectInfo.nameOf("/Users/foo/claude-code-java"));
        assertEquals("lanterna", ProjectInfo.nameOf("/Users/foo/lanterna"));
        assertEquals("wake", ProjectInfo.nameOf("C:\\Users\\foo\\wake"));
    }

    @Test
    void nameOfToleratesTrailingSeparatorAndRoot() {
        assertEquals("proj", ProjectInfo.nameOf("/a/proj/"));
        assertEquals("Unknown project", ProjectInfo.nameOf("/"));
    }

    @Test
    void nameOfFallsBackForBlank() {
        assertEquals("Unknown project", ProjectInfo.nameOf(""));
        assertEquals("Unknown project", ProjectInfo.nameOf(null));
    }
}
