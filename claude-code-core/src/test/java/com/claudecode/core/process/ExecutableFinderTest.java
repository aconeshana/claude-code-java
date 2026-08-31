package com.claudecode.core.process;

import com.claudecode.core.platform.Platform;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutableFinderTest {
    @TempDir Path temp;

    @Test void resolvesExecutableFromPath() throws Exception {
        Path executable = temp.resolve("demo");
        Files.writeString(executable, "#!/bin/sh\n");
        assertTrue(executable.toFile().setExecutable(true));
        assertEquals(executable.toAbsolutePath(), ExecutableFinder.find("demo",
            Map.of("PATH", temp.toString()), temp.resolve("cwd"), Platform.LINUX).orElseThrow());
    }

    @Test void resolvePreservesArgumentsWhenMissing() {
        var result = ExecutableFinder.resolve("definitely-not-a-command", List.of("a", "b"));
        assertEquals("definitely-not-a-command", result.cmd());
        assertEquals(List.of("a", "b"), result.args());
    }
}
