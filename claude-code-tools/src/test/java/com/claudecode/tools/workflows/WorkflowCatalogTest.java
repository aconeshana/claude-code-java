package com.claudecode.tools.workflows;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowCatalogTest {

    @TempDir Path temp;

    @Test
    void loadsJsFilesFromUserAndAncestorProjectDirectoriesWithProjectPrecedence() throws IOException {
        Path userDir = temp.resolve("home-workflows");
        Path project = temp.resolve("repo");
        Path nested = project.resolve("a/b");
        Files.createDirectories(userDir);
        Files.createDirectories(nested);
        write(userDir.resolve("same.js"), "same", "user");
        write(project.resolve(".claude/workflows/same.js"), "same", "project");
        write(project.resolve(".claude/workflows/project-only.js"), "project-only", "project only");
        Files.writeString(userDir.resolve("ignored.md"), "not a workflow");

        WorkflowDefinition bundled = definition("same", "bundled", WorkflowSource.BUILT_IN);
        WorkflowDefinition plugin = definition("plugin-only", "plugin", WorkflowSource.PLUGIN);
        WorkflowCatalog catalog = new WorkflowCatalog(userDir, List.of(bundled), () -> List.of(plugin));

        List<WorkflowDefinition> loaded = catalog.load(nested);

        assertEquals(List.of("plugin-only", "project-only", "same"),
            loaded.stream().map(d -> d.metadata().name()).toList());
        WorkflowDefinition same = loaded.stream()
            .filter(d -> Strings.CS.equals(d.metadata().name(), "same"))
            .findFirst().orElseThrow();
        assertEquals(WorkflowSource.PROJECT, same.source());
        assertEquals("project", same.metadata().description());
    }

    @Test
    void skipsInvalidAndOversizedScripts() throws IOException {
        Path userDir = temp.resolve("workflows");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("invalid.js"), "console.log('before meta')");
        Files.writeString(userDir.resolve("huge.js"), "x".repeat(WorkflowCatalog.MAX_SCRIPT_BYTES + 1));

        WorkflowCatalog catalog = new WorkflowCatalog(userDir, List.of(), List::of);

        assertTrue(catalog.load(temp).isEmpty());
    }

    private static void write(Path path, String name, String description) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "export const meta = { name: \"" + name
            + "\", description: \"" + description + "\" };\nreturn \"ok\";");
    }

    private static WorkflowDefinition definition(String name, String description, WorkflowSource source) {
        String script = "export const meta = { name: \"" + name
            + "\", description: \"" + description + "\" };\nreturn \"ok\";";
        ParsedWorkflowScript parsed = WorkflowScriptParser.parse(script);
        return new WorkflowDefinition(parsed.metadata(), script, parsed.body(), source, null, null, false, false);
    }
}
