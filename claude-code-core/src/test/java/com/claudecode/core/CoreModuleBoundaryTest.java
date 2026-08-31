package com.claudecode.core;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Prevents core source sets from depending on higher-level application modules.
 */
class CoreModuleBoundaryTest {

    private static final Pattern FORBIDDEN_IMPORT = Pattern.compile(
        "^\\s*import\\s+(?:static\\s+)?com\\.claudecode\\.(commands|tools|ui)\\.");

    @Test
    void productionAndTestSourcesDoNotImportHigherLevelModules() throws IOException {
        List<String> violations = Stream.of(Path.of("src/main/java"), Path.of("src/test/java"))
            .flatMap(sourceRoot -> javaFiles(sourceRoot).stream())
            .flatMap(CoreModuleBoundaryTest::forbiddenImports)
            .toList();

        assertTrue(violations.isEmpty(),
            () -> "core may not import commands, tools, or ui:\n" + String.join("\n", violations));
    }

    private static List<Path> javaFiles(Path sourceRoot) {
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths
                .filter(path -> Strings.CS.endsWith(path.toString(), ".java"))
                .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect source root " + sourceRoot, exception);
        }
    }

    private static Stream<String> forbiddenImports(Path source) {
        try {
            return Files.readAllLines(source).stream()
                .filter(line -> FORBIDDEN_IMPORT.matcher(line).find())
                .map(line -> source + ": " + line.strip());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect source file " + source, exception);
        }
    }
}
