package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.serialization.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionRequestBodyTest {

    @Test
    void editBuildsDedicatedFileQuestionAndStructuredDiff(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("Example.java");
        Files.writeString(file, "class Example {\n  int oldValue;\n}\n");
        var input = JsonUtils.getMapper().createObjectNode()
            .put("file_path", file.toString())
            .put("old_string", "  int oldValue;")
            .put("new_string", "  int newValue;");

        PermissionRequestBody.FileChange body = assertInstanceOf(
            PermissionRequestBody.FileChange.class,
            PermissionRequestBody.from(PermissionAskContext.simple("Edit", input, "toolu_edit")));

        assertEquals("Edit file", body.title());
        assertTrue(Strings.CS.endsWith(body.subtitle(), "Example.java"));
        assertEquals("Do you want to make this edit to Example.java?", body.question());
        assertTrue(body.hunks().getFirst().lines().contains("-  int oldValue;"));
        assertTrue(body.hunks().getFirst().lines().contains("+  int newValue;"));
    }

    @Test
    void writeExistingFileBuildsOverwriteQuestionAndStructuredDiff(@TempDir Path temp)
            throws Exception {
        Path file = temp.resolve("settings.yml");
        Files.writeString(file, "enabled: false\n");
        var input = JsonUtils.getMapper().createObjectNode()
            .put("file_path", file.toString())
            .put("content", "enabled: true\n");

        PermissionRequestBody.FileChange body = assertInstanceOf(
            PermissionRequestBody.FileChange.class,
            PermissionRequestBody.from(PermissionAskContext.simple("Write", input, "toolu_write")));

        assertEquals("Overwrite file", body.title());
        assertTrue(Strings.CS.endsWith(body.subtitle(), "settings.yml"));
        assertEquals("Do you want to overwrite settings.yml?", body.question());
        assertTrue(body.hunks().getFirst().lines().contains("-enabled: false"));
        assertTrue(body.hunks().getFirst().lines().contains("+enabled: true"));
    }

    @Test
    void writeNewFileBuildsCreateQuestionAndAddedContentPreview(@TempDir Path temp) {
        Path file = temp.resolve("new.yml");
        var input = JsonUtils.getMapper().createObjectNode()
            .put("file_path", file.toString())
            .put("content", "enabled: true\n");

        PermissionRequestBody.FileChange body = assertInstanceOf(
            PermissionRequestBody.FileChange.class,
            PermissionRequestBody.from(PermissionAskContext.simple("Write", input, "toolu_write")));

        assertEquals("Create file", body.title());
        assertEquals("Do you want to create new.yml?", body.question());
        assertTrue(body.hunks().isEmpty());
        assertEquals("enabled: true\n", body.contentPreview());
    }

    @Test
    void notebookReplaceBuildsDedicatedQuestionAndCellDiff(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("analysis.ipynb");
        Files.writeString(file, "{\"cells\":[{\"id\":\"cell-0\",\"cell_type\":\"code\",\"source\":[\"print(1)\"]}]}");
        var input = JsonUtils.getMapper().createObjectNode()
            .put("notebook_path", file.toString())
            .put("cell_id", "cell-0")
            .put("new_source", "print(2)")
            .put("cell_type", "code")
            .put("edit_mode", "replace");

        PermissionRequestBody.NotebookEdit body = assertInstanceOf(
            PermissionRequestBody.NotebookEdit.class,
            PermissionRequestBody.from(PermissionAskContext.simple(
                "NotebookEdit", input, "toolu_notebook")));

        assertEquals("Edit notebook", body.title());
        assertEquals("Do you want to make this edit to analysis.ipynb?", body.question());
        assertEquals("Replace cell contents for cell cell-0 (code)", body.description());
        assertTrue(body.hunks().getFirst().lines().contains("-print(1)"));
        assertTrue(body.hunks().getFirst().lines().contains("+print(2)"));
    }

    @Test
    void bashSedInPlaceBuildsFileDiffAndSimulatedApprovalInput(@TempDir Path temp)
            throws Exception {
        Path file = temp.resolve("settings.yml");
        Files.writeString(file, "enabled: false\n");
        var input = JsonUtils.getMapper().createObjectNode()
            .put("command", "sed -i '' 's/enabled: false/enabled: true/' '" + file + "'");

        PermissionRequestBody.SedEdit body = assertInstanceOf(
            PermissionRequestBody.SedEdit.class,
            PermissionRequestBody.from(PermissionAskContext.simple("Bash", input, "toolu_sed")));

        assertEquals("Edit file", body.title());
        assertEquals("Do you want to make this edit to settings.yml?", body.question());
        assertTrue(body.hunks().getFirst().lines().contains("-enabled: false"));
        assertTrue(body.hunks().getFirst().lines().contains("+enabled: true"));
        assertEquals(file.toString(), body.updatedInput().at("/_simulatedSedEdit/filePath").asText());
        assertEquals("enabled: true\n",
            body.updatedInput().at("/_simulatedSedEdit/newContent").asText());
    }

    @Test
    void mcpBuildsFallbackStyleInvocationWithJsonValuesAndMcpSuffix() {
        var input = JsonUtils.getMapper().createObjectNode()
            .put("query", "octocat")
            .put("limit", 10);

        PermissionRequestBody.Mcp body = assertInstanceOf(
            PermissionRequestBody.Mcp.class,
            PermissionRequestBody.from(PermissionAskContext
                .builder("mcp__github__search_users", input)
                .toolUseId("toolu_mcp")
                .toolDescription("Search GitHub users")
                .build()));

        assertEquals("Tool use", body.title());
        assertEquals(
            "github - search_users(query: \"octocat\", limit: 10) (MCP)",
            body.invocation());
        assertEquals("Search GitHub users", body.description());
    }

    @Test
    void mcpPermissionUsesVerboseArgumentsWithoutCompactModeTruncation() {
        String longValue = "x".repeat(120);
        var input = JsonUtils.getMapper().createObjectNode().put("payload", longValue);

        PermissionRequestBody.Mcp body = assertInstanceOf(
            PermissionRequestBody.Mcp.class,
            PermissionRequestBody.from(PermissionAskContext.simple(
                "mcp__demo__send", input, "toolu_mcp_long")));

        assertTrue(Strings.CS.contains(body.invocation(), longValue));
    }

    @Test
    void permissionDialogMountsDedicatedBodies(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("Example.java");
        Files.writeString(file, "old\n");
        var editInput = JsonUtils.getMapper().createObjectNode()
            .put("file_path", file.toString())
            .put("old_string", "old")
            .put("new_string", "new");
        PermissionDialog editDialog = new PermissionDialog();
        editDialog.show(PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("Edit", editInput, "toolu_edit")),
            null, _ -> {}, _ -> {}, () -> {});

        assertEquals("Edit file", editDialog.titleForTest());
        assertTrue(Strings.CS.endsWith(editDialog.subtitleForTest(), "Example.java"));
        assertEquals("Do you want to make this edit to Example.java?",
            editDialog.questionForTest());
        assertTrue(editDialog.specialBodyLinesForTest().stream()
            .anyMatch(line -> Strings.CS.contains(line, "old")));
        assertTrue(editDialog.specialBodyLinesForTest().stream()
            .anyMatch(line -> Strings.CS.contains(line, "new")));

        var mcpInput = JsonUtils.getMapper().createObjectNode().put("query", "octocat");
        PermissionDialog mcpDialog = new PermissionDialog();
        mcpDialog.show(PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple(
                    "mcp__github__search_users", mcpInput, "toolu_mcp")),
            null, _ -> {}, _ -> {}, () -> {});

        assertEquals("Tool use", mcpDialog.titleForTest());
        assertEquals(List.of("github - search_users(query: \"octocat\") (MCP)"),
            mcpDialog.specialBodyLinesForTest());
    }

    @Test
    void permissionDialogSeparatesMultipleFileDiffHunks(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("multi.txt");
        String middle = IntStream.range(0, 30)
            .mapToObj(i -> "middle-" + i)
            .collect(Collectors.joining("\n"));
        Files.writeString(file, "target\n" + middle + "\ntarget\n");
        var input = JsonUtils.getMapper().createObjectNode()
            .put("file_path", file.toString())
            .put("old_string", "target")
            .put("new_string", "changed")
            .put("replace_all", true);
        PermissionDialog dialog = new PermissionDialog();

        dialog.show(PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("Edit", input, "toolu_multi")),
            null, _ -> {}, _ -> {}, () -> {});

        assertTrue(dialog.specialBodyLinesForTest().contains("..."),
            dialog.specialBodyLinesForTest().toString());
    }
}
