package com.claudecode.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FileTextUtilsTest {
    @TempDir Path temp;

    @Test void readsEncodingAndPreservesLineEndingMetadata() throws Exception {
        Path file = temp.resolve("utf16.txt");
        Files.write(file, "\ufeffa\r\nb\r\n".getBytes(StandardCharsets.UTF_16LE));
        var value = FileTextUtils.readWithMetadata(file);
        assertEquals(StandardCharsets.UTF_16LE, value.charset());
        assertEquals(FileTextUtils.LineEnding.CRLF, value.lineEnding());
        assertEquals("\ufeffa\nb\n", value.content());
        assertEquals("\ufeffa\r\nb\r\n", FileTextUtils.restoreLineEndings(value.content(), value.lineEnding()));
    }

    @Test void sharesLineNumberAndTabRules() {
        assertEquals("3\ta\n4\tb", FileTextUtils.addLineNumbers("a\nb", 3));
        assertEquals("a\nb", FileTextUtils.stripLineNumberPrefixes("3\ta\n     4→b"));
        assertEquals("  a\n    b", FileTextUtils.convertLeadingTabsToSpaces("\ta\n\t\tb"));
    }
}
