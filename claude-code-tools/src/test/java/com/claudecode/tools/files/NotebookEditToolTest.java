package com.claudecode.tools.files;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class NotebookEditToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String NOTEBOOK = """
            {
             "cells": [
              {"cell_type": "code", "id": "cell-0", "metadata": {}, \
            "source": ["print(1)"], "execution_count": null, "outputs": []}
             ],
             "metadata": {"kernelspec": {}},
             "nbformat": 4,
             "nbformat_minor": 5
            }\
            """;

    private ToolExecutionContext ctx(Path cwd, FileStateCache cache) {
        return ToolExecutionContext.builder(new AbortController(), "test-session")
            .workingDirectory(cwd.toString())
            .fileStateCache(cache)
            .build();
    }

    /** Pre-seed the read-before-write cache so the guard passes for a given notebook. */
    private void markRead(Path notebookPath, FileStateCache cache) throws Exception {
        String abs = notebookPath.toAbsolutePath().normalize().toString();
        String content = Files.readString(notebookPath, StandardCharsets.UTF_8);
        long mtime = Files.getLastModifiedTime(notebookPath).toMillis();
        cache.set(abs, new FileStateCache.FileState(content, mtime, null, null, false));
    }

    private ObjectNode input(String path, String cellId, String newSource,
                             String cellType, String editMode) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("notebook_path", path);
        if (cellId != null) n.put("cell_id", cellId);
        if (newSource != null) n.put("new_source", newSource);
        if (cellType != null) n.put("cell_type", cellType);
        if (editMode != null) n.put("edit_mode", editMode);
        return n;
    }

    // ---- Gap 1: .ipynb extension gate ----
    @Test
    void rejectsNonIpynbExtension(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("notes.txt");
        Files.writeString(f, "hello");
        String out = new NotebookEditTool().call(
            input("notes.txt", "cell-0", "x", "code", "replace"), ctx(dir, new FileStateCache()));
        assertTrue(Strings.CS.contains(out, "File must be a Jupyter notebook (.ipynb file)"),
            "expected .ipynb rejection, got: " + out);
    }

    @Test
    void acceptsIpynbExtension(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("nb.ipynb");
        Files.writeString(f, NOTEBOOK);
        FileStateCache cache = new FileStateCache();
        markRead(f, cache);
        String out = new NotebookEditTool().call(
            input("nb.ipynb", "cell-0", "print(2)", "code", "replace"), ctx(dir, cache));
        assertTrue(Strings.CS.contains(out, "Updated cell cell-0"), "expected update, got: " + out);
    }

    @Test
    void successfulReplaceMapsStructuredNotebookOutput(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("nb.ipynb");
        Files.writeString(f, NOTEBOOK);
        FileStateCache cache = new FileStateCache();
        markRead(f, cache);
        NotebookEditTool tool = new NotebookEditTool();
        ObjectNode input = input("nb.ipynb", "cell-0", "print(2)", "code", "replace");
        var invocation = tool.callWithResult(input, ctx(dir, cache));
        String raw = invocation.rawResult();
        ToolResult mapped = invocation.mappedResult();

        assertEquals(raw, ((TextBlock) mapped.content().getFirst()).text());
        ObjectNode payload = (ObjectNode) mapped.toolUseResult();
        assertEquals("cell-0", payload.path("cell_id").asText());
        assertEquals("print(2)", payload.path("new_source").asText());
        assertEquals("code", payload.path("cell_type").asText());
        assertEquals("replace", payload.path("edit_mode").asText());
        assertEquals(f.toAbsolutePath().normalize().toString(), payload.path("notebook_path").asText());
        assertTrue(Strings.CS.contains(payload.path("original_file").asText(), "print(1)"));
        assertTrue(Strings.CS.contains(payload.path("updated_file").asText(), "print(2)"));
    }

    // ---- Gap 2: cell_type required for insert ----
    @Test
    void insertRejectsMissingCellType(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("nb.ipynb");
        Files.writeString(f, NOTEBOOK);
        String out = new NotebookEditTool().call(
            input("nb.ipynb", "cell-0", "print(2)", null, "insert"), ctx(dir, new FileStateCache()));
        assertTrue(Strings.CS.contains(out, "Cell type is required when using edit_mode=insert"),
            "expected insert/cell_type rejection, got: " + out);
    }

    @Test
    void insertSucceedsWithCellType(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("nb.ipynb");
        Files.writeString(f, NOTEBOOK);
        FileStateCache cache = new FileStateCache();
        markRead(f, cache);
        String out = new NotebookEditTool().call(
            input("nb.ipynb", "cell-0", "print(2)", "markdown", "insert"), ctx(dir, cache));
        assertTrue(Strings.CS.contains(out, "Inserted cell cell-0"), "expected insert, got: " + out);
        // The inserted (markdown) cell must have cell_type=markdown and no
        // execution_count/outputs — only the original code cell-0 keeps those.
        ArrayNode cells = notebookCells(f);
        ObjectNode inserted = (ObjectNode) cells.get(1);
        assertEquals("markdown", inserted.get("cell_type").asText());
        assertFalse(inserted.has("execution_count"), "markdown cell must not have execution_count");
        assertFalse(inserted.has("outputs"), "markdown cell must not have outputs");
    }

    @Test
    void deleteSucceeds(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("nb.ipynb");
        Files.writeString(f, NOTEBOOK);
        FileStateCache cache = new FileStateCache();
        markRead(f, cache);
        String out = new NotebookEditTool().call(
            input("nb.ipynb", "cell-0", null, null, "delete"), ctx(dir, cache));
        assertTrue(Strings.CS.contains(out, "Deleted cell cell-0"), "expected delete, got: " + out);
    }


    // call replace->insert conversion can run; the "one past the end" branch is
    // therefore unreachable through the validated path. A replace against a
    // non-existent cell index must be rejected, not silently inserted.
    @Test
    void replaceWithOutOfBoundsIndexIsRejected(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("nb.ipynb");
        Files.writeString(f, NOTEBOOK);
        FileStateCache cache = new FileStateCache();
        markRead(f, cache);
        String out = new NotebookEditTool().call(
            input("nb.ipynb", "cell-99", "print(9)", null, "replace"), ctx(dir, cache));
        assertTrue(Strings.CS.contains(out, "Cell with index 99 does not exist"),
            "expected out-of-bounds rejection, got: " + out);
    }

    private ArrayNode notebookCells(Path f) throws Exception {
        ObjectNode nb = (ObjectNode) MAPPER.readTree(Files.readString(f, StandardCharsets.UTF_8));
        return (ArrayNode) nb.get("cells");
    }
}
