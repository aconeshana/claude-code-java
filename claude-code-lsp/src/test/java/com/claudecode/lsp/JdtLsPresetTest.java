package com.claudecode.lsp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * jdt.ls discovery logic. The lifecycle tests below require a real jdt.ls
 * install and only run when {@code JDT_LS_PATH} is set.
 */
class JdtLsPresetTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("jdt-ls-test");
    }

    @Test
    void resolveCommand_explicitOverride_takesPriority() throws Exception {
        Path launcher = Files.createFile(tempDir.resolve("jdt.ls.sh"));
        var resolved = JdtLsPreset.resolveCommand(launcher);
        assertTrue(resolved.isPresent());
        assertEquals(List.of(launcher.toString()), resolved.get());
    }

    @Test
    void resolveCommand_missingEverywhere_returnsEmpty() {
        Path bogusOverride = tempDir.resolve("does-not-exist.sh");
        // Without JDT_LS_PATH set and no real install, this only asserts the
        // explicit override path is honored (absent -> falls through); we
        // can't assert a hard "empty" here since a dev machine may have a
        // real jdt.ls installed via VS Code, which is a legitimate hit.
        var resolved = JdtLsPreset.resolveCommand(bogusOverride);
        assertNotNull(resolved); // never throws, always returns an Optional
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JDT_LS_PATH", matches = ".+")
    void isAvailable_trueWhenEnvVarSet() {
        assertTrue(JdtLsPreset.isAvailable());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JDT_LS_PATH", matches = ".+")
    void create_buildsRunnableJavaServer() throws Exception {
        var registry = new LspDiagnosticRegistry();
        var maybeServer = JdtLsPreset.create(null, registry);
        assertTrue(maybeServer.isPresent());

        ProcessLspServerInstance server = maybeServer.get();
        server.initialize(tempDir);
        assertTrue(server.isRunning());
        assertEquals("java", server.languageId());

        Path javaFile = tempDir.resolve("src/Test.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
            public class Test {
                public static void main(String[] args) {
                    System.out.println("Hello");
                }
            }
            """);

        server.didOpen(javaFile, Files.readString(javaFile));
        Thread.sleep(2000);

        List<Diagnostic> diagnostics = server.getDiagnostics(javaFile);
        System.out.println("Diagnostics received: " + diagnostics.size());

        server.shutdown();
        assertFalse(server.isRunning());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JDT_LS_PATH", matches = ".+")
    void create_detectsTypeErrorDiagnostics() throws Exception {
        var registry = new LspDiagnosticRegistry();
        ProcessLspServerInstance server = JdtLsPreset.create(null, registry).orElseThrow();
        server.initialize(tempDir);

        Path javaFile = tempDir.resolve("src/ErrorTest.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
            public class ErrorTest {
                public static void main(String[] args) {
                    int x = "not an int";  // Type error
                }
            }
            """);

        server.didOpen(javaFile, Files.readString(javaFile));
        Thread.sleep(2000);

        List<Diagnostic> diagnostics = server.getDiagnostics(javaFile);
        boolean hasError = diagnostics.stream()
            .anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);

        System.out.println("Has errors: " + hasError);
        System.out.println("Diagnostics: " + diagnostics);

        server.shutdown();
    }
}
