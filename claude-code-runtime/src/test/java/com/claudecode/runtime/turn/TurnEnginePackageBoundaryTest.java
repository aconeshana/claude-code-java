package com.claudecode.runtime.turn;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the headless boundary: the runtime turn package must not import Lanterna,
 * UI adapters, or service implementations. It scans the package's own sources so
 * the invariant remains available in offline builds.
 *
 * <p>This is what keeps the future physical split to a {@code runtime} module a pure
 * "move the package" operation: nothing here transitively drags in a UI type.
 */
class TurnEnginePackageBoundaryTest {

    @Test
    void turnPackageDoesNotImportLanternaOrItsAdapter() throws IOException {
        // Tests run with the module directory as the working directory.
        Path dir = Path.of("src/main/java/com/claudecode/runtime/turn");
        assertTrue(Files.isDirectory(dir),
            "turn source dir should exist: " + dir.toAbsolutePath());

        try (Stream<Path> paths = Files.walk(dir)) {
            List<String> offenders = paths
                .filter(p -> Strings.CS.endsWith(p.toString(), ".java"))
                .flatMap(TurnEnginePackageBoundaryTest::importLines)
                .filter(line -> Strings.CS.contains(line, "import com.googlecode.lanterna")
                             || Strings.CS.contains(line, "import com.claudecode.ui.")
                             || Strings.CS.contains(line, "import com.claudecode.services."))
                .toList();
            assertTrue(offenders.isEmpty(),
                "the turn package must stay headless; offending imports:\n"
                    + String.join("\n", offenders));
        }
    }

    private static Stream<String> importLines(Path javaFile) {
        try {
            return Files.readAllLines(javaFile).stream()
                .map(String::trim)
                .filter(l -> Strings.CS.startsWith(l, "import "))
                .map(l -> javaFile.getFileName() + ": " + l);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
