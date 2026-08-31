package com.claudecode.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileReadCacheTest {
    @TempDir Path temp;

    @Test void cachesAndInvalidatesByModificationTime() throws Exception {
        Path file = temp.resolve("value.txt");
        Files.writeString(file, "first");
        FileReadCache cache = new FileReadCache();
        assertEquals("first", cache.read(file).content());
        assertEquals(1, cache.stats().size());
        cache.invalidate(file);
        Files.writeString(file, "second");
        assertEquals("second", cache.read(file).content());
        cache.clear();
        assertEquals(0, cache.stats().size());
    }
}
