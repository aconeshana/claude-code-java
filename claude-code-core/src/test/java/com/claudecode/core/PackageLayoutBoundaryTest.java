package com.claudecode.core;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the repository-wide package layout after decomposing the former catch-all packages.
 */
class PackageLayoutBoundaryTest {

    private static final Pattern LEGACY_DECLARATION_OR_IMPORT = Pattern.compile(
        "^\\s*(?:package|import\\s+(?:static\\s+)?)\\s*com\\.claudecode\\.(?:utils|schema)(?:\\.|;)");
    private static final Path SCHEMA_VALIDATOR_PATH = Path.of(
        "claude-code-tools/src/main/java/com/claudecode/tools/validation/SchemaValidator.java");

    @Test
    void legacyUtilsAndSchemaPackagesAreAbsentFromJavaSources() throws IOException {
        Path repositoryRoot = repositoryRoot();

        List<String> violations = javaSources(repositoryRoot).stream()
            .flatMap(PackageLayoutBoundaryTest::legacyDeclarationsAndImports)
            .toList();

        assertTrue(violations.isEmpty(),
            () -> "legacy com.claudecode.utils/schema declarations or imports remain:\n"
                + String.join("\n", violations));
    }

    @Test
    void schemaValidatorExistsOnlyInToolsValidation() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<Path> validators = javaSources(repositoryRoot).stream()
            .filter(path -> Strings.CS.equals(path.getFileName().toString(), "SchemaValidator.java"))
            .map(repositoryRoot::relativize)
            .sorted()
            .toList();

        assertEquals(List.of(SCHEMA_VALIDATOR_PATH), validators,
            "SchemaValidator must have one owner: com.claudecode.tools.validation");
    }

    @Test
    void keybindingsPackageRemainsAllowed() {
        assertFalse(LEGACY_DECLARATION_OR_IMPORT.matcher("package com.claudecode.keybindings;").find());
        assertFalse(LEGACY_DECLARATION_OR_IMPORT.matcher(
            "import com.claudecode.keybindings.KeybindingResolver;").find());
        assertFalse(LEGACY_DECLARATION_OR_IMPORT.matcher(
            "import static com.claudecode.keybindings.DefaultBindings.defaults;").find());
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("claude-code-core"))
                && Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root from " + Path.of("").toAbsolutePath());
    }

    private static List<Path> javaSources(Path repositoryRoot) throws IOException {
        List<Path> sourceRoots;
        try (Stream<Path> modules = Files.list(repositoryRoot)) {
            sourceRoots = modules.filter(Files::isDirectory)
                .flatMap(module -> Stream.of(
                    module.resolve("src/main/java"), module.resolve("src/test/java")))
                .filter(Files::isDirectory)
                .toList();
        }
        List<Path> result = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                paths.filter(PackageLayoutBoundaryTest::isJavaSource).forEach(result::add);
            }
        }
        return List.copyOf(result);
    }

    private static boolean isJavaSource(Path path) {
        return Files.isRegularFile(path) && Strings.CS.endsWith(path.getFileName().toString(), ".java");
    }

    private static Stream<String> legacyDeclarationsAndImports(Path source) {
        try {
            return Files.readAllLines(source).stream()
                .filter(line -> LEGACY_DECLARATION_OR_IMPORT.matcher(line).find())
                .map(line -> source + ": " + line.strip());
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot inspect " + source, exception);
        }
    }
}
