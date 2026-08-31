package com.claudecode.core.lsp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolAtPositionTest {

    @TempDir Path tempDir;

    private Path write(String name, String content) throws Exception {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void identifier_found() throws Exception {
        Path f = write("A.java", "int foo = 1;");
        Optional<String> s = SymbolAtPosition.symbolAt(f, 0, 4);
        assertTrue(s.isPresent());
        assertEquals("foo", s.get());
    }

    @Test
    void operator_found() throws Exception {
        Path f = write("A.java", "a + b");
        Optional<String> s = SymbolAtPosition.symbolAt(f, 0, 2);
        assertTrue(s.isPresent());
        assertEquals("+", s.get());
    }

    @Test
    void outOfRangeLine_empty() throws Exception {
        Path f = write("A.java", "int foo = 1;");
        assertFalse(SymbolAtPosition.symbolAt(f, 5, 0).isPresent());
        assertFalse(SymbolAtPosition.symbolAt(f, -1, 0).isPresent());
    }

    @Test
    void outOfRangeCharacter_empty() throws Exception {
        Path f = write("A.java", "int foo = 1;");
        assertFalse(SymbolAtPosition.symbolAt(f, 0, 100).isPresent());
        assertFalse(SymbolAtPosition.symbolAt(f, 0, -1).isPresent());
    }

    @Test
    void nonexistentFile_empty() {
        assertFalse(SymbolAtPosition.symbolAt(tempDir.resolve("nope.java"), 0, 0).isPresent());
    }

    @Test
    void directory_empty() throws Exception {
        assertFalse(SymbolAtPosition.symbolAt(tempDir, 0, 0).isPresent());
    }

    @Test
    void lastLineTruncatedWhenBufferFull_empty() throws Exception {
// Single line longer than 64KB — fully fills the buffer, so the only line is the truncated
// final line → empty.
        Path f = write("big.txt", "x".repeat(70_000));
        assertFalse(SymbolAtPosition.symbolAt(f, 0, 0).isPresent());
    }

    @Test
    void earlyLineWhenBufferFull_found() throws Exception {
        // First line is short; the rest is a huge single (no-newline) line that
        // fills the buffer. Line 0 is NOT the truncated final line → resolved.
        Path f = write("big.txt", "int foo = 1;\n" + "x".repeat(70_000));
        Optional<String> s = SymbolAtPosition.symbolAt(f, 0, 4);
        assertTrue(s.isPresent());
        assertEquals("foo", s.get());
    }

    @Test
    void multibyteDecode_splitsCorrectly() throws Exception {
        // Multibyte content on line 0 must not corrupt later-line resolution.
        Path f = write("A.java", "日本語テスト\nint bar = 2;");
        Optional<String> s = SymbolAtPosition.symbolAt(f, 1, 4);
        assertTrue(s.isPresent());
        assertEquals("bar", s.get());
    }

    @Test
    void symbolTruncatedAtThirty() throws Exception {
        String forty = "a".repeat(40);
        Path f = write("A.java", forty);
        Optional<String> s = SymbolAtPosition.symbolAt(f, 0, 0);
        assertTrue(s.isPresent());
        assertEquals(30, s.get().length());
    }
}
