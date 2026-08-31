package com.claudecode.tools.workflows;

import org.apache.commons.lang3.Strings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;




public final class WorkflowCatalog {

    public static final int MAX_SCRIPT_BYTES = WorkflowScriptParser.MAX_SCRIPT_BYTES;
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowCatalog.class);

    private final Path userWorkflowsDir;
    private final List<WorkflowDefinition> bundled;
    private final Supplier<List<WorkflowDefinition>> pluginWorkflows;

    public WorkflowCatalog(Path userWorkflowsDir,
                           List<WorkflowDefinition> bundled,
                           Supplier<List<WorkflowDefinition>> pluginWorkflows) {
        this.userWorkflowsDir = Objects.requireNonNull(userWorkflowsDir, "userWorkflowsDir");
        this.bundled = List.copyOf(bundled == null ? List.of() : bundled);
        this.pluginWorkflows = pluginWorkflows == null ? List::of : pluginWorkflows;
    }

    public List<WorkflowDefinition> load(Path cwd) {
        Objects.requireNonNull(cwd, "cwd");
        Map<String, WorkflowDefinition> byName = new LinkedHashMap<>();
        merge(byName, bundled);
        merge(byName, safePluginWorkflows());
        merge(byName, loadDirectory(userWorkflowsDir, WorkflowSource.USER));
        for (Path directory : projectWorkflowDirectories(cwd.toAbsolutePath().normalize())) {
            merge(byName, loadDirectory(directory, WorkflowSource.PROJECT));
        }
        return byName.values().stream()
            .sorted(Comparator.comparing(def -> def.metadata().name()))
            .toList();
    }




    public Optional<WorkflowDefinition> find(String name, Path cwd) {
        if (name == null) return Optional.empty();
        return load(cwd).stream()
            .filter(definition -> definition.metadata().name().equals(name))
            .findFirst();
    }

    private List<WorkflowDefinition> safePluginWorkflows() {
        try {
            List<WorkflowDefinition> workflows = pluginWorkflows.get();
            return workflows == null ? List.of() : workflows;
        } catch (RuntimeException e) {
            LOG.warn("Failed to load plugin workflows: {}", e.getMessage());
            return List.of();
        }
    }

    private static void merge(Map<String, WorkflowDefinition> target,
                              List<WorkflowDefinition> definitions) {
        for (WorkflowDefinition definition : definitions) {
            if (definition != null) target.put(definition.metadata().name(), definition);
        }
    }

    private static List<Path> projectWorkflowDirectories(Path cwd) {
        List<Path> ancestors = new ArrayList<>();
        for (Path path = cwd; path != null; path = path.getParent()) ancestors.add(path);
        List<Path> result = new ArrayList<>();
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            result.add(ancestors.get(i).resolve(".claude").resolve("workflows"));
        }
        return result;
    }

    private static List<WorkflowDefinition> loadDirectory(Path directory, WorkflowSource source) {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                .filter(path -> Strings.CS.endsWith(path.getFileName().toString(), ".js"))
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .map(path -> loadFile(path, source))
                .filter(Objects::nonNull)
                .toList();
        } catch (IOException e) {
            LOG.warn("Failed to read workflow directory {}: {}", directory, e.getMessage());
            return List.of();
        }
    }

    private static WorkflowDefinition loadFile(Path path, WorkflowSource source) {
        try {
            long size = Files.size(path);
            if (size > MAX_SCRIPT_BYTES) {
                LOG.warn("Skipping oversized workflow script {} ({} bytes)", path, size);
                return null;
            }
            String script = Files.readString(path, StandardCharsets.UTF_8);
            ParsedWorkflowScript parsed = WorkflowScriptParser.parse(script);
            return new WorkflowDefinition(parsed.metadata(), script, parsed.body(),
                source, path.toAbsolutePath().normalize(), null, false, false);
        } catch (IOException | WorkflowScriptException e) {
            LOG.warn("Skipping invalid workflow script {}: {}", path, e.getMessage());
            return null;
        }
    }
}
