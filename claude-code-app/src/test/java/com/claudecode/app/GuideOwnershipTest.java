package com.claudecode.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the ownership rules that the guides still describe in prose: concrete
 * tools and commands stay in their owning modules, and the two guides name the
 * same product modules as {@code settings.gradle.kts}.
 */
class GuideOwnershipTest {

    private static final Pattern EXTENDS_TOOL = Pattern.compile(
        "\\b(?:class|interface|enum|record)\\s+(\\w+)\\b[^{;]*\\bextends\\s+Tool\\b");
    private static final Pattern IMPLEMENTS_COMMAND = Pattern.compile(
        "\\b(?:class|interface|enum|record)\\s+(\\w+)\\b[^{;]*\\bimplements\\s+Command\\b");
    private static final Pattern GUIDE_MODULE = Pattern.compile(
        "^(claude-code-[a-z0-9-]+)\\s+[\u2192\\-]", Pattern.MULTILINE);
    private static final Pattern SETTINGS_MODULE = Pattern.compile(
        "^\\s*\"(claude-code-[a-z0-9-]+)\"", Pattern.MULTILINE);

    @Test
    void concreteToolsLiveInTheToolsModule() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = repositoryRoot();
        for (String module : productModules(root)) {
            if (Strings.CS.equals(module, "claude-code-tools")) continue;
            Path source = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(source)) continue;
            try (Stream<Path> files = Files.walk(source)) {
                files.filter(path -> Strings.CS.endsWith(path.toString(), ".java")).forEach(path -> {
                    String text = read(path);
                    Matcher matcher = EXTENDS_TOOL.matcher(text);
                    while (matcher.find()) {
                        offenders.add(root.relativize(path) + " declares " + matcher.group(1));
                    }
                });
            }
        }
        assertTrue(offenders.isEmpty(), () -> "tool implementations belong in claude-code-tools:\n"
            + String.join("\n", offenders));
    }

    @Test
    void concreteCommandsLiveInTheCommandsModuleOrCliAdapter() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = repositoryRoot();
        Set<String> allowed = Set.of("claude-code-commands", "claude-code-cli");
        for (String module : productModules(root)) {
            if (allowed.contains(module)) continue;
            Path source = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(source)) continue;
            try (Stream<Path> files = Files.walk(source)) {
                files.filter(path -> Strings.CS.endsWith(path.toString(), ".java")).forEach(path -> {
                    String text = read(path);
                    Matcher matcher = IMPLEMENTS_COMMAND.matcher(text);
                    while (matcher.find()) {
                        offenders.add(root.relativize(path) + " declares " + matcher.group(1));
                    }
                });
            }
        }
        assertTrue(offenders.isEmpty(), () -> "command implementations belong in claude-code-commands:\n"
            + String.join("\n", offenders));
    }

    @Test
    void guidesNameTheSameModulesAsTheGradleBuild() throws IOException {
        Path root = repositoryRoot();
        Set<String> gradle = modulesIn(root.resolve("settings.gradle.kts"), SETTINGS_MODULE);
        Set<String> agents = moduleMap(root.resolve("AGENTS.md"));
        Set<String> claude = moduleMap(root.resolve("CLAUDE.md"));
        assertTrue(agents.equals(gradle) && claude.equals(gradle),
            () -> "module lists drifted:\n  gradle=" + gradle + "\n  AGENTS=" + agents
                + "\n  CLAUDE=" + claude);
    }

    private static Set<String> productModules(Path root) throws IOException {
        return modulesIn(root.resolve("settings.gradle.kts"), SETTINGS_MODULE);
    }

    private static Set<String> moduleMap(Path guide) throws IOException {
        String text = Files.readString(guide);
        int start = text.indexOf("## Module Map");
        int end = text.indexOf("```", text.indexOf("```", start) + 3);
        return modulesInText(text.substring(start, end), GUIDE_MODULE);
    }

    private static Set<String> modulesIn(Path file, Pattern pattern) throws IOException {
        return modulesInText(Files.readString(file), pattern);
    }

    private static Set<String> modulesInText(String text, Pattern pattern) {
        Set<String> modules = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) modules.add(matcher.group(1));
        return modules;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(path.toString(), e);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))
                && Files.isDirectory(current.resolve("claude-code-core"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate repository root");
    }
}
