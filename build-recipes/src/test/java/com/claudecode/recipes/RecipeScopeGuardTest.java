package com.claudecode.recipes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeScopeGuardTest {

    @Test
    void recipesDoNotBindToProductFilesOrTypes() throws IOException {
        Path recipes = Path.of("src/main/java/com/claudecode/recipes");
        try (var files = Files.list(recipes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertFalse(source.contains("getSourcePath()"),
                    () -> file + " must match Java semantics, not a source filename");
                assertFalse(source.matches("(?s).*com\\.claudecode\\.(?!recipes(?:\\.|;)).*"),
                    () -> file + " must not couple a recipe to a product type");
            }
        }
    }

    @Test
    void unnecessaryVoidReturnCleanupRemainsActive() throws IOException {
        String build = Files.readString(Path.of("../build.gradle.kts"));
        assertTrue(build.contains(
            "\"org.openrewrite.staticanalysis.UnnecessaryReturnAsLastStatement\""));
    }
}
