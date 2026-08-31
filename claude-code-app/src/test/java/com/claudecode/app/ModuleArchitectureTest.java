package com.claudecode.app;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Executable guard for the repository-level module boundaries documented in
 * {@code docs/architecture.md}.
 *
 * <p>The test deliberately uses only the JDK and JUnit. It reads the module Gradle files,
 * checks the allowed internal dependency direction, rejects cycles and transitive
 * dependency leaks, and scans low-level integration/runtime modules plus the UI
 * boundary for forbidden imports. This keeps the architecture invariant available
 * even in offline builds.
 */
class ModuleArchitectureTest {

    private static final Pattern PROJECT_DEPENDENCY = Pattern.compile(
        "([A-Za-z][A-Za-z0-9]*)\\(\\s*project\\(\\s*[\"']:(claude-code-[^\"']+)"
            + "[\"']\\s*\\)\\s*\\)");
    private static final Pattern INTERNAL_IMPORT = Pattern.compile(
        "^import\\s+(?:static\\s+)?(com\\.claudecode\\.[A-Za-z0-9_$.]+)(?:\\.\\*)?;");

    private static final Map<String, Set<String>> ALLOWED_DEPENDENCIES = Map.ofEntries(
        Map.entry("claude-code-core", Set.of()),
        Map.entry("claude-code-http", Set.of()),
        Map.entry("claude-code-api", Set.of("claude-code-core", "claude-code-http")),
        Map.entry("claude-code-permissions", Set.of("claude-code-core")),
        Map.entry("claude-code-session", Set.of("claude-code-core")),
        Map.entry("claude-code-lsp", Set.of("claude-code-core")),
        Map.entry("claude-code-mcp", Set.of("claude-code-core", "claude-code-http")),
        Map.entry("claude-code-runtime", Set.of("claude-code-core", "claude-code-permissions")),
        Map.entry("claude-code-tools", Set.of(
            "claude-code-core", "claude-code-http", "claude-code-permissions",
            "claude-code-session", "claude-code-lsp", "claude-code-mcp",
            "claude-code-runtime")),
        Map.entry("claude-code-services", Set.of(
            "claude-code-core", "claude-code-http", "claude-code-api", "claude-code-mcp",
            "claude-code-permissions", "claude-code-session", "claude-code-tools",
            "claude-code-runtime")),
        Map.entry("claude-code-commands", Set.of(
            "claude-code-core", "claude-code-runtime")),
        Map.entry("claude-code-ui", Set.of(
            "claude-code-core", "claude-code-commands", "claude-code-permissions",
            "claude-code-tools", "claude-code-lsp",
            "claude-code-runtime")),
        Map.entry("claude-code-cli", Set.of(
            "claude-code-core", "claude-code-http", "claude-code-api",
            "claude-code-permissions", "claude-code-runtime", "claude-code-session",
            "claude-code-mcp", "claude-code-commands", "claude-code-ui",
            "claude-code-tools", "claude-code-services", "claude-code-lsp")),
        Map.entry("claude-code-sdk", Set.of("claude-code-core", "claude-code-session", "claude-code-cli")),
        Map.entry("claude-code-app", Set.of("claude-code-cli"))
    );

    private static final Map<String, Set<String>> OWNED_PACKAGES = Map.ofEntries(
        Map.entry("claude-code-core", Set.of("com.claudecode.core", "com.claudecode.keybindings")),
        Map.entry("claude-code-http", Set.of("com.claudecode.http")),
        Map.entry("claude-code-api", Set.of("com.claudecode.api")),
        Map.entry("claude-code-permissions", Set.of("com.claudecode.permissions")),
        Map.entry("claude-code-runtime", Set.of("com.claudecode.runtime")),
        Map.entry("claude-code-tools", Set.of("com.claudecode.tools")),
        Map.entry("claude-code-commands", Set.of("com.claudecode.commands")),
        Map.entry("claude-code-mcp", Set.of("com.claudecode.mcp")),
        Map.entry("claude-code-session", Set.of("com.claudecode.session")),
        Map.entry("claude-code-services", Set.of("com.claudecode.services")),
        Map.entry("claude-code-ui", Set.of("com.claudecode.ui")),
        Map.entry("claude-code-lsp", Set.of("com.claudecode.lsp")),
        Map.entry("claude-code-cli", Set.of("com.claudecode.cli")),
        Map.entry("claude-code-sdk", Set.of("com.claudecode.sdk")),
        Map.entry("claude-code-app", Set.of("com.claudecode.app"))
    );

    private static final Set<ModuleEdge> EXPORTED_PROJECT_DEPENDENCIES = Set.of();
    private static final Set<String> PRODUCTION_DEPENDENCY_CONFIGURATIONS = Set.of(
        "api", "implementation", "compileOnly", "runtimeOnly");
    private static final Set<String> TEST_COMPILE_DEPENDENCY_CONFIGURATIONS = Set.of(
        "testImplementation", "testCompileOnly");

    @Test
    void internalGradleGraphUsesOnlyAllowedAcyclicEdges() throws Exception {
        Path root = repositoryRoot();
        Map<String, Set<String>> actual = new LinkedHashMap<>();
        List<String> forbiddenEdges = new ArrayList<>();
        for (String module : ALLOWED_DEPENDENCIES.keySet()) {
            Path buildFile = root.resolve(module).resolve("build.gradle.kts");
            assertTrue(Files.isRegularFile(buildFile), "missing module build file: " + buildFile);
            Set<String> dependencies = internalDependencies(buildFile);
            actual.put(module, dependencies);
            for (String dependency : dependencies) {
                if (!ALLOWED_DEPENDENCIES.get(module).contains(dependency)) {
                    forbiddenEdges.add(module + " -> " + dependency);
                }
            }
        }

        assertTrue(forbiddenEdges.isEmpty(), () -> "forbidden module dependencies:\n"
            + String.join("\n", forbiddenEdges));
        assertAcyclic(actual);
    }

    @Test
    void executableJarEnablesNativeAccessForBundledJna() throws IOException {
        String buildScript = Files.readString(
            repositoryRoot().resolve("claude-code-app/build.gradle.kts"));

        assertTrue(Strings.CS.contains(buildScript, "Enable-Native-Access")
                && Strings.CS.contains(buildScript, "ALL-UNNAMED"),
            "java -jar must enable native access before the macOS settings watcher loads JNA");
    }

    @Test
    void productionImportsHaveDirectProjectDependencies() throws IOException {
        Path root = repositoryRoot();
        List<String> offenders = new ArrayList<>();
        for (String module : ALLOWED_DEPENDENCIES.keySet()) {
            Path sourceRoot = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) continue;
            Set<String> declared = compileDependencies(
                root.resolve(module).resolve("build.gradle.kts"), false);
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                paths.filter(path -> Strings.CS.endsWith(path.toString(), ".java"))
                    .forEach(path -> internalImports(path).forEach(importedType -> {
                        String owner = ownerOf(importedType);
                        if (owner != null && !owner.equals(module) && !declared.contains(owner)) {
                            offenders.add(root.relativize(path) + ": imports " + importedType
                                + " from undeclared project " + owner);
                        }
                    }));
            }
        }
        assertTrue(offenders.isEmpty(), () -> "transitive project dependencies leaked into source:\n"
            + String.join("\n", offenders));
    }

    @Test
    void testImportsHaveDirectProjectDependencies() throws IOException {
        Path root = repositoryRoot();
        List<String> offenders = new ArrayList<>();
        for (String module : ALLOWED_DEPENDENCIES.keySet()) {
            Path sourceRoot = root.resolve(module).resolve("src/test/java");
            if (!Files.isDirectory(sourceRoot)) continue;
            Set<String> declared = compileDependencies(
                root.resolve(module).resolve("build.gradle.kts"), true);
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                paths.filter(path -> Strings.CS.endsWith(path.toString(), ".java"))
                    .forEach(path -> internalImports(path).forEach(importedType -> {
                        String owner = ownerOf(importedType);
                        if (owner != null && !owner.equals(module) && !declared.contains(owner)) {
                            offenders.add(root.relativize(path) + ": imports " + importedType
                                + " from undeclared test project " + owner);
                        }
                    }));
            }
        }
        assertTrue(offenders.isEmpty(), () -> "transitive project dependencies leaked into tests:\n"
            + String.join("\n", offenders));
    }

    @Test
    void internalProjectDependenciesUseImplementationByDefault() throws IOException {
        Path root = repositoryRoot();
        List<String> offenders = new ArrayList<>();
        for (String module : ALLOWED_DEPENDENCIES.keySet()) {
            Path buildFile = root.resolve(module).resolve("build.gradle.kts");
            for (ProjectDependency dependency : projectDependencies(buildFile)) {
                ModuleEdge edge = new ModuleEdge(module, dependency.module());
                if (Strings.CS.equals("api", dependency.configuration())
                    && !EXPORTED_PROJECT_DEPENDENCIES.contains(edge)) {
                    offenders.add(edge + " is declared with api");
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "internal dependencies must use implementation"
            + " unless their types are deliberately exported in the public ABI:\n"
            + String.join("\n", offenders));
    }

    @Test
    void lowLevelModulesDoNotImportHigherLevelImplementationPackages() throws IOException {
        Path root = repositoryRoot();
        assertNoImports(root.resolve("claude-code-lsp/src/main/java"), Set.of(
            "com.claudecode.ui.", "com.claudecode.tools.", "com.claudecode.services.",
            "com.claudecode.commands.", "com.claudecode.permissions."));
        assertNoImports(root.resolve("claude-code-mcp/src/main/java"), Set.of(
            "com.claudecode.ui.", "com.claudecode.tools.", "com.claudecode.services.",
            "com.claudecode.commands.", "com.claudecode.permissions."));
        assertNoImports(root.resolve("claude-code-runtime/src/main/java"), Set.of(
            "com.claudecode.ui.", "com.claudecode.services.", "com.claudecode.commands.",
            "com.claudecode.cli.", "com.googlecode.lanterna."));
        assertNoImports(root.resolve("claude-code-ui/src/main/java"), Set.of(
            "com.claudecode.services.", "com.claudecode.mcp.",
            "com.claudecode.session."));
        assertNoImports(root.resolve("claude-code-commands/src/main/java"), Set.of(
            "com.claudecode.services.", "com.claudecode.mcp.",
            "com.claudecode.session.", "com.claudecode.permissions.",
            "com.claudecode.tools."));
    }

    @Test
    void queryImplementationIsOwnedByRuntimeAndHiddenFromOtherModules() throws IOException {
        Path root = repositoryRoot();
        Path coreSource = root.resolve("claude-code-core/src/main/java");
        assertNoProductionType(coreSource, "QueryEngine.java");
        assertNoProductionType(coreSource, "QueryEngineConfig.java");
        assertTrue(Files.notExists(coreSource.resolve("com/claudecode/core/engine/query")),
            "core.engine.query must move behind the runtime query-session boundary");

        for (String module : ALLOWED_DEPENDENCIES.keySet()) {
            if (Strings.CS.equals("claude-code-runtime", module)) continue;
            assertNoImports(root.resolve(module).resolve("src/main/java"), Set.of(
                "com.claudecode.runtime.query.internal.",
                "com.claudecode.runtime.query.DefaultQuerySession;",
                "com.claudecode.runtime.query.QueryLoop;",
                "com.claudecode.runtime.query.QueryHelpers;",
                "com.claudecode.runtime.query.QueryParams;",
                "com.claudecode.runtime.query.QueryDeps;",
                "com.claudecode.runtime.query.CallModelAdapter;"));
        }

        List<String> directConstruction = new ArrayList<>();
        Path runtimeQuery = root.resolve("claude-code-runtime/src/main/java/com/claudecode/runtime/query");
        try (Stream<Path> paths = Files.walk(runtimeQuery)) {
            paths.filter(path -> Strings.CS.endsWith(path.toString(), ".java"))
                .filter(path -> !Strings.CS.equals(
                    "DefaultQuerySessionFactory.java", path.getFileName().toString()))
                .filter(path -> fileContains(path, "new DefaultQuerySession("))
                .forEach(path -> directConstruction.add(root.relativize(path).toString()));
        }
        assertTrue(directConstruction.isEmpty(), () ->
            "DefaultQuerySession must only be created by its factory:\n"
                + String.join("\n", directConstruction));
    }

    @Test
    void commandsConsumeOnlyStableCoreAndRuntimeBoundaries() throws IOException {
        Path root = repositoryRoot();
        Path commandsSource = root.resolve("claude-code-commands/src/main/java");
        assertNoImports(commandsSource, Set.of(
            "com.claudecode.session.",
            "com.claudecode.permissions.",
            "com.claudecode.tools."));

        List<String> forbiddenConstructions = new ArrayList<>();
        List<String> forbiddenTokens = List.of(
            "new SessionManager(",
            "new SessionStorage(",
            "new SessionSearch(",
            "TaskRegistry.global()",
            "LoopPromptResolver.global()",
            "TeammateContextHolder",
            "PlatformSandboxManager.create()");
        try (Stream<Path> paths = Files.walk(commandsSource)) {
            paths.filter(path -> Strings.CS.endsWith(path.toString(), ".java"))
                .filter(path -> forbiddenTokens.stream().anyMatch(token -> fileContains(path, token)))
                .forEach(path -> forbiddenConstructions.add(root.relativize(path).toString()));
        }
        assertTrue(forbiddenConstructions.isEmpty(), () ->
            "commands must consume application ports instead of provider implementations:\n"
                + String.join("\n", forbiddenConstructions));
    }

    @Test
    void interactivePresentationDoesNotAssembleInfrastructureOrResolveGlobals() throws IOException {
        Path root = repositoryRoot();
        Path uiSource = root.resolve("claude-code-ui/src/main/java");
        List<String> forbiddenTokens = List.of(
            "new SessionManager(", "new SessionStorage(", "new SessionSearch(",
            "new StatsAggregator(", "TaskRegistry.global()", "WorkflowRunStore.global()",
            "InvokedSkillRegistry.global()", "LoopWakeupManager.global()");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(uiSource)) {
            paths.filter(path -> Strings.CS.endsWith(path.toString(), ".java"))
                .filter(path -> forbiddenTokens.stream().anyMatch(token -> fileContains(path, token)))
                .forEach(path -> offenders.add(root.relativize(path).toString()));
        }
        assertTrue(offenders.isEmpty(), () ->
            "UI must consume injected session and feature runtime boundaries:\n"
                + String.join("\n", offenders));
    }

    @Test
    void cliOwnsInteractiveRuntimeAggregateConstruction() throws IOException {
        Path root = repositoryRoot();
        List<String> constructors = List.of(
            "new ReplApplicationPorts(", "new ReplFeatureRuntime(",
            "new ReplLaunchState(", "new ReplWiring(");
        List<String> offenders = new ArrayList<>();
        for (String module : ALLOWED_DEPENDENCIES.keySet()) {
            Path source = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(source)) continue;
            try (Stream<Path> paths = Files.walk(source)) {
                paths.filter(path -> Strings.CS.endsWith(path.toString(), ".java"))
                    .filter(path -> constructors.stream().anyMatch(token -> fileContains(path, token)))
                    .filter(path -> !Strings.CS.equals("CliInteractiveRuntimeAssembler.java",
                        path.getFileName().toString()))
                    .forEach(path -> offenders.add(root.relativize(path).toString()));
            }
        }
        assertTrue(offenders.isEmpty(), () ->
            "interactive runtime aggregates must be constructed only by CLI assembler:\n"
                + String.join("\n", offenders));
    }

    @Test
    void cliIsTheOnlyProductionFactoryCompositionRoot() throws IOException {
        Path root = repositoryRoot();
        List<String> offenders = new ArrayList<>();
        for (String module : ALLOWED_DEPENDENCIES.keySet()) {
            if (Strings.CS.equals("claude-code-cli", module)) continue;
            Path sourceRoot = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) continue;
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                paths.filter(path -> Strings.CS.endsWith(path.toString(), ".java"))
                    .filter(path -> fileContains(path, "new DefaultQuerySessionFactory("))
                    .forEach(path -> offenders.add(root.relativize(path).toString()));
            }
        }
        assertTrue(offenders.isEmpty(), () -> "only CLI may create DefaultQuerySessionFactory:\n"
            + String.join("\n", offenders));
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
        throw new IllegalStateException(
            "cannot locate repository root from " + Path.of("").toAbsolutePath());
    }

    private static Set<String> internalDependencies(Path buildFile) throws IOException {
        Set<String> dependencies = new LinkedHashSet<>();
        for (ProjectDependency dependency : projectDependencies(buildFile)) {
            if (PRODUCTION_DEPENDENCY_CONFIGURATIONS.contains(dependency.configuration())) {
                dependencies.add(dependency.module());
            }
        }
        return Set.copyOf(dependencies);
    }

    private static Set<String> compileDependencies(Path buildFile, boolean includeTests)
        throws IOException {
        Set<String> dependencies = new LinkedHashSet<>();
        for (ProjectDependency dependency : projectDependencies(buildFile)) {
            boolean production = PRODUCTION_DEPENDENCY_CONFIGURATIONS.contains(
                dependency.configuration());
            boolean test = includeTests && TEST_COMPILE_DEPENDENCY_CONFIGURATIONS.contains(
                dependency.configuration());
            if (production || test) dependencies.add(dependency.module());
        }
        return Set.copyOf(dependencies);
    }

    private static List<ProjectDependency> projectDependencies(Path buildFile) throws IOException {
        Matcher matcher = PROJECT_DEPENDENCY.matcher(Files.readString(buildFile));
        List<ProjectDependency> dependencies = new ArrayList<>();
        while (matcher.find()) {
            dependencies.add(new ProjectDependency(matcher.group(1), matcher.group(2)));
        }
        return List.copyOf(dependencies);
    }

    private static Stream<String> internalImports(Path javaFile) {
        try {
            return Files.readAllLines(javaFile).stream()
                .map(String::trim)
                .map(INTERNAL_IMPORT::matcher)
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String ownerOf(String importedType) {
        for (Map.Entry<String, Set<String>> entry : OWNED_PACKAGES.entrySet()) {
            for (String packageName : entry.getValue()) {
                if (importedType.equals(packageName)
                    || Strings.CS.startsWith(importedType, packageName + ".")) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private static void assertAcyclic(Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String module : graph.keySet()) {
            visit(module, graph, visited, active, path);
        }
    }

    private static void visit(String module, Map<String, Set<String>> graph,
                              Set<String> visited, Set<String> active, Deque<String> path) {
        if (visited.contains(module)) return;
        if (!active.add(module)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(module);
            fail("module dependency cycle: " + String.join(" -> ", cycle));
        }
        path.addLast(module);
        for (String dependency : graph.getOrDefault(module, Set.of())) {
            visit(dependency, graph, visited, active, path);
        }
        path.removeLast();
        active.remove(module);
        visited.add(module);
    }

    private static void assertNoImports(Path sourceRoot, Set<String> forbiddenPrefixes)
        throws IOException {
        if (!Files.isDirectory(sourceRoot)) return;
        List<String> offenders;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            offenders = paths.filter(path -> Strings.CS.endsWith(path.toString(), ".java"))
                .flatMap(ModuleArchitectureTest::importLines)
                .filter(line -> forbiddenPrefixes.stream().anyMatch(line::contains))
                .toList();
        }
        assertTrue(offenders.isEmpty(), () -> "forbidden module imports:\n"
            + String.join("\n", offenders));
    }

    private static void assertNoProductionType(Path sourceRoot, String fileName)
        throws IOException {
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().equals(fileName)),
                () -> fileName + " must not remain in core production sources");
        }
    }

    private static boolean fileContains(Path path, String text) {
        try {
            return Strings.CS.contains(Files.readString(path), text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Stream<String> importLines(Path javaFile) {
        try {
            return Files.readAllLines(javaFile).stream()
                .map(String::trim)
                .filter(line -> Strings.CS.startsWith(line, "import "))
                .map(line -> javaFile + ": " + line);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record ProjectDependency(String configuration, String module) {}

    private record ModuleEdge(String source, String target) {
        @Override
        public String toString() {
            return source + " -> " + target;
        }
    }
}
