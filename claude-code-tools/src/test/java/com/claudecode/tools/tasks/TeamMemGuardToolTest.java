package com.claudecode.tools.tasks;
import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.claudecode.tools.files.FileEditTool;
import com.claudecode.tools.files.FileWriteTool;

class TeamMemGuardToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;

    private static final String GITHUB_PAT = "ghp_0123456789abcdefABCDEF0123456789abcd";

    private String teamMemDir;
    private FileWriteTool writeTool;
    private FileEditTool editTool;

    @BeforeEach
    void setUp() {
        teamMemDir = TeamMemPaths.getTeamMemPath(tempDir.toString());
        writeTool = new FileWriteTool();
        editTool = new FileEditTool();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Team-mem dir resolves under ~/.claude/projects; remove exactly this dir.
        Path dir = Path.of(teamMemDir);
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception _) { }
                    });
            }
        }
    }

    private ToolExecutionContext ctx(boolean enabled) {
        return ToolExecutionContext.builder(new AbortController(), "test-session")
            .workingDirectory(tempDir.toString())
            .fileStateCache(new FileStateCache())
            .nestedMemoryAttachmentTriggers(ConcurrentHashMap.newKeySet())
            .loadedNestedMemoryPaths(ConcurrentHashMap.newKeySet())
            .teamMemoryEnabled(enabled)
            .build();
    }

    private ObjectNode writeInput(String filePath, String content) {
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", filePath);
        input.put("content", content);
        return input;
    }

    @Test
    void writeToTeamMemWithSecretIsBlockedWhenEnabled() {
        ToolExecutionContext ctx = ctx(true);
        Object result = writeTool.call(writeInput(teamMemDir + "MEMORY.md", "token=" + GITHUB_PAT), ctx);
        assertInstanceOf(String.class, result, "expected a blocking error");
        assertTrue(Strings.CS.contains(((String) result), "team memory"));
        assertFalse(Strings.CS.contains(((String) result), GITHUB_PAT), "secret leaked into message");
    }

    @Test
    void writeToTeamMemWithoutSecretSucceedsWhenEnabled() {
        ToolExecutionContext ctx = ctx(true);
        Object result = writeTool.call(writeInput(teamMemDir + "MEMORY.md", "all good"), ctx);
        assertInstanceOf(StructuredToolOutput.class, result);
    }

    @Test
    void writeToTeamMemWithSecretAllowedWhenDisabled() {
        ToolExecutionContext ctx = ctx(false);
        Object result = writeTool.call(writeInput(teamMemDir + "MEMORY.md", "token=" + GITHUB_PAT), ctx);
        assertInstanceOf(StructuredToolOutput.class, result);
    }

    @Test
    void editIntroducingSecretIntoTeamMemIsBlockedWhenEnabled() throws Exception {
        String target = teamMemDir + "notes.md";
        Files.createDirectories(Path.of(teamMemDir));
        Files.writeString(Path.of(target), "hello world\n");

        ToolExecutionContext ctx = ctx(true);
        // Register the file as read so the read-before-write gate passes.
        ctx.fileStateCache().set(target,
            new FileStateCache.FileState("hello world\n",
                Files.getLastModifiedTime(Path.of(target)).toMillis(), null, null, false));

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", target);
        input.put("old_string", "hello world");
        input.put("new_string", "hello " + GITHUB_PAT);

        Object result = editTool.call(input, ctx);
        assertInstanceOf(String.class, result, "expected a blocking error");
        assertTrue(Strings.CS.contains(((String) result), "team memory"));
        assertFalse(Strings.CS.contains(((String) result), GITHUB_PAT), "secret leaked into message");
    }

    @Test
    void editIntroducingSecretIntoTeamMemAllowedWhenDisabled() throws Exception {
        String target = teamMemDir + "notes.md";
        Files.createDirectories(Path.of(teamMemDir));
        Files.writeString(Path.of(target), "hello world\n");

        ToolExecutionContext ctx = ctx(false);
        ctx.fileStateCache().set(target,
            new FileStateCache.FileState("hello world\n",
                Files.getLastModifiedTime(Path.of(target)).toMillis(), null, null, false));

        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", target);
        input.put("old_string", "hello world");
        input.put("new_string", "hello " + GITHUB_PAT);

        Object result = editTool.call(input, ctx);
        assertInstanceOf(StructuredToolOutput.class, result);
    }
}
