package com.claudecode.services.claudemd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryFileScannerConfigHomeTest {

    @Test
    void configHomeFactoryReadsUserMemoryDirectlyFromOverride(@TempDir Path temp) throws Exception {
        Path configHome = temp.resolve("custom-config");
        Files.createDirectories(configHome);
        Path memory = configHome.resolve("CLAUDE.md");
        Files.writeString(memory, "custom user memory");

        MemoryFileScanner scanner = MemoryFileScanner.forConfigHome(configHome, List.of(), null);

        assertTrue(scanner.scan(temp.resolve("project"), List.of(), Set.of(MemoryType.USER))
            .stream().anyMatch(file -> file.path().equals(memory)));
    }
}
