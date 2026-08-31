package com.claudecode.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/** Keeps process-global standard streams at the CLI and terminal adapter boundaries. */
class StandardStreamBoundaryTest {

    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
    private static final Pattern STANDARD_STREAM = Pattern.compile("System\\.(?:out|err)");
    private static final Pattern STANDARD_STREAM_BINDING =
        Pattern.compile("CliOutput\\.system(?:Out|Err)\\s*\\(");
    private static final Pattern INHERITED_PROCESS_IO = Pattern.compile("\\.inheritIO\\s*\\(");
    private static final Set<String> ALLOWED_BINDINGS = Set.of(
        "claude-code-cli/src/main/java/com/claudecode/cli/CliOutput.java",
        "claude-code-ui/src/main/java/com/claudecode/ui/lanterna/repl/TuiOutputGuard.java"
    );
    private static final Set<String> ALLOWED_INTERACTIVE_CHILDREN = Set.of(
        "claude-code-commands/src/main/java/com/claudecode/commands/impl/context/MemoryCommand.java",
        "claude-code-ui/src/main/java/com/claudecode/ui/lanterna/input/ExternalEditorLauncher.java",
        "claude-code-ui/src/main/java/com/claudecode/ui/lanterna/repl/LanternaReplScreen.java"
    );
    private static final Set<String> ALLOWED_CLI_COMPOSITION_ROOTS = Set.of(
        "claude-code-cli/src/main/java/com/claudecode/cli/CliOutput.java",
        "claude-code-cli/src/main/java/com/claudecode/cli/CliSessionAssembler.java",
        "claude-code-cli/src/main/java/com/claudecode/cli/ClaudeCodeCli.java"
    );

    @Test
    void productionCodeUsesStandardStreamsOnlyAtExplicitAdapters() throws IOException {
        Path root = repositoryRoot();
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> isProductionJavaSource(root, path))
                .forEach(path -> inspect(root, path, offenders));
        }

        assertTrue(offenders.isEmpty(), () -> "Direct standard-stream access must be routed through"
            + " CliOutput or TuiOutputGuard:\n" + String.join("\n", offenders));
    }

    @Test
    void childProcessesInheritTerminalOnlyDuringExplicitEditorHandoffs() throws IOException {
        Path root = repositoryRoot();
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> isProductionJavaSource(root, path))
                .forEach(path -> inspectInheritedIo(root, path, offenders));
        }

        assertTrue(offenders.isEmpty(), () -> "inheritIO bypasses the TUI output guard; only"
            + " explicit editor handoffs may use it:\n" + String.join("\n", offenders));
    }

    @Test
    void cliBusinessCodeReceivesInjectedOutputPorts() throws IOException {
        Path root = repositoryRoot();
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root.resolve("claude-code-cli/src/main/java"))) {
            paths.filter(StandardStreamBoundaryTest::isJavaSource)
                .forEach(path -> inspectCliBinding(root, path, offenders));
        }

        assertTrue(offenders.isEmpty(), () -> "Only CLI composition roots may bind CliOutput"
            + " to process-global streams:\n" + String.join("\n", offenders));
    }

    private static void inspect(Path root, Path file, List<String> offenders) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (ALLOWED_BINDINGS.contains(relative)) return;
        try {
            String source = COMMENTS.matcher(Files.readString(file)).replaceAll("");
            if (STANDARD_STREAM.matcher(source).find()) offenders.add(relative);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void inspectInheritedIo(Path root, Path file, List<String> offenders) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (ALLOWED_INTERACTIVE_CHILDREN.contains(relative)) return;
        try {
            String source = COMMENTS.matcher(Files.readString(file)).replaceAll("");
            if (INHERITED_PROCESS_IO.matcher(source).find()) offenders.add(relative);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void inspectCliBinding(Path root, Path file, List<String> offenders) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (ALLOWED_CLI_COMPOSITION_ROOTS.contains(relative)) return;
        try {
            String source = COMMENTS.matcher(Files.readString(file)).replaceAll("");
            if (STANDARD_STREAM_BINDING.matcher(source).find()) offenders.add(relative);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isProductionJavaSource(Path root, Path path) {
        if (!isJavaSource(path)) return false;
        Path relative = root.relativize(path);
        return relative.getNameCount() >= 5
            && Strings.CS.equals("src", relative.getName(1).toString())
            && Strings.CS.equals("main", relative.getName(2).toString())
            && Strings.CS.equals("java", relative.getName(3).toString());
    }

    private static boolean isJavaSource(Path path) {
        String fileName = path.getFileName().toString();
        int extensionSeparator = fileName.lastIndexOf('.');
        return Files.isRegularFile(path)
            && extensionSeparator >= 0
            && Strings.CS.equals("java", fileName.substring(extensionSeparator + 1));
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("repository root not found");
        return current;
    }
}
