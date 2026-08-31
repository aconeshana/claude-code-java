package com.claudecode.services.claudemd;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryFileInfoTest {

    @Test
    void constructor_copiesGlobsForImmutability() {
        List<String> mutable = new ArrayList<>();
        mutable.add("**/*.ts");
        MemoryFileInfo info = new MemoryFileInfo(
            Path.of("/x/CLAUDE.md"), MemoryType.PROJECT, "body", mutable, null);
        // Add to the source list — MemoryFileInfo must not observe it.
        mutable.add("src/**/*.tsx");
        assertEquals(1, info.globs().size());
    }

    @Test
    void constructor_preservesNullGlobs() {
        MemoryFileInfo info = new MemoryFileInfo(
            Path.of("/x"), MemoryType.USER, "", null, null);
        assertNull(info.globs(), "null globs stays null (semantically 'no gate')");
    }

    @Test
    void isImported_falseForRoot() {
        MemoryFileInfo info = new MemoryFileInfo(
            Path.of("/x"), MemoryType.USER, "", null, null);
        assertFalse(info.isImported());
    }

    @Test
    void isImported_trueWhenParentSet() {
        MemoryFileInfo child = new MemoryFileInfo(
            Path.of("/x/child.md"), MemoryType.USER, "", null, Path.of("/x/CLAUDE.md"));
        assertTrue(child.isImported());
        assertEquals(Path.of("/x/CLAUDE.md"), child.parent());
    }

    @Test
    void constructor_rejectsNullPath() {
        assertThrows(NullPointerException.class,
            () -> new MemoryFileInfo(null, MemoryType.USER, "", null, null));
    }

    @Test
    void constructor_rejectsNullType() {
        assertThrows(NullPointerException.class,
            () -> new MemoryFileInfo(Path.of("/x"), null, "", null, null));
    }
}
