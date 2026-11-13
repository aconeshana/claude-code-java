package com.claudecode.core.process;

import com.claudecode.core.platform.Platform;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Assumptions;
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

    /**
     * Windows environment blocks name the variable "Path" (the JDK's System.getenv view
     * resolves it case-insensitively), but find() consumes plain map copies such as
     * SubprocessEnvironment.snapshot(), where the lookup is case-sensitive. The finder
     * must locate PATH whatever case the block uses or every bare-command lookup on a
     * default Windows install returns empty.
     */
    @Test void resolvesExecutableFromWindowsPathCasedEnvironment() throws Exception {
        Assumptions.assumeTrue(Platform.IS_WINDOWS);
        Path executable = temp.resolve("demo.exe");
        Files.writeString(executable, "MZ fake executable");
        assertEquals(executable.toAbsolutePath(), ExecutableFinder.find("demo",
            Map.of("Path", temp.toString()), temp.resolve("cwd"), Platform.WIN32).orElseThrow());
    }

    @Test void posixPathLookupStaysCaseSensitive() throws Exception {
        Assumptions.assumeFalse(Platform.IS_WINDOWS);
        Path executable = temp.resolve("demo");
        Files.writeString(executable, "#!/bin/sh\n");
        assertTrue(executable.toFile().setExecutable(true));
        // A differently-cased variable is not the POSIX PATH; bare lookup stays empty.
        assertThrows(NoSuchElementException.class, () -> ExecutableFinder.find("demo",
            Map.of("Path", temp.toString()), temp.resolve("cwd"), Platform.LINUX).orElseThrow());
    }

    @Test void findsLiveExecutableThroughSnapshotEnvironmentOnWindows() {
        Assumptions.assumeTrue(Platform.IS_WINDOWS);
        // Production path: snapshot() copies System.getenv() into a plain map whose
        // block name is "Path". System32 ships where.exe on every Windows install.
        assertTrue(ExecutableFinder.find("where").isPresent());
    }

    /** A junk PATH entry (illegal path characters) must not abort the remaining lookup. */
    @Test void skipsUnparsablePathEntries() throws Exception {
        Assumptions.assumeTrue(Platform.IS_WINDOWS);
        Path executable = temp.resolve("demo.exe");
        Files.writeString(executable, "MZ fake executable");
        assertEquals(executable.toAbsolutePath(), ExecutableFinder.find("demo",
            Map.of("Path", "\"C:\\broken\"dir;" + temp), temp.resolve("cwd"),
            Platform.WIN32).orElseThrow());
    }

    @Test void resolvePreservesArgumentsWhenMissing() {
        var result = ExecutableFinder.resolve("definitely-not-a-command", List.of("a", "b"));
        assertEquals("definitely-not-a-command", result.cmd());
        assertEquals(List.of("a", "b"), result.args());
    }
}
