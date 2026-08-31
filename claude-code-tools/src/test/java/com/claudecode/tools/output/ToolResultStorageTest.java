package com.claudecode.tools.output;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.ToolResultContentForm;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ImageBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.commons.lang3.Strings;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolBuilder;


class ToolResultStorageTest {

    @Test
    void rejectsNullMappedResultLikeTheTsNonNullableContract() {
        assertThrows(NullPointerException.class,
            () -> ToolResultStorage.process(null, null, null));
    }

    @Test
    void previewHonorsCallerProvidedLimitAndPrefersNearbyNewline() {
        assertEquals("abc", ToolResultStorage.preview("abc", 3));
        assertEquals("abcdefgh", ToolResultStorage.preview("abcdefghij", 8));
        assertEquals("12345", ToolResultStorage.preview("12345\n67890", 8));
    }

    @Test
    void persistsLargeTextAndKeepsStructuredMetadata(@TempDir Path tempDir) throws Exception {
        Tool<ObjectNode, String> tool = new ToolBuilder<ObjectNode, String>()
            .name("fixture")
            .description("fixture")
            .call((_, _) -> "")
            .build();
        String body = "line\n".repeat(20_000);
        ToolResult original = ToolResult.success(body).withToolUseResult(List.of("metadata"));
        ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "session-1").workingDirectory(tempDir.toString()).build().withToolUseId("toolu/large");

        ToolResult processed = ToolResultStorage.process(original, tool, context,
            (_, _) -> tempDir.resolve("tool-results"));

        assertEquals(original.toolUseResult(), processed.toolUseResult());
        assertInstanceOf(TextBlock.class, processed.content().getFirst());
        String message = ((TextBlock) processed.content().getFirst()).text();
        assertTrue(Strings.CS.startsWith(message, "<persisted-output>\n"));
        assertTrue(Strings.CS.contains(message, "Full output saved to:"));
        Path persisted;
        try (var files = Files.list(tempDir.resolve("tool-results"))) {
            persisted = files.findFirst().orElseThrow();
        }
        assertTrue(Strings.CS.startsWith(persisted.getFileName().toString(), "toolu_large-"));
        assertTrue(Strings.CS.endsWith(persisted.getFileName().toString(), ".txt"));
        assertTrue(Files.exists(persisted));
        assertEquals(body, Files.readString(persisted, StandardCharsets.UTF_8));
    }

    @Test
    void leavesNonTextBlocksUntouched(@TempDir Path tempDir) {
        Tool<ObjectNode, String> tool = new ToolBuilder<ObjectNode, String>()
            .name("fixture")
            .description("fixture")
            .call((_, _) -> "")
            .build();
        ToolResult original = new ToolResult(List.of(
            new TextBlock("x".repeat(60_000)),
            new ImageBlock(NullNode.getInstance())
        ), false);
        ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "session-1").workingDirectory(tempDir.toString()).build().withToolUseId("toolu-mixed");

        ToolResult processed = ToolResultStorage.process(original, tool, context,
            (_, _) -> tempDir.resolve("tool-results"));

        assertEquals(original, processed);
    }

    @Test
    void persistsMultipleTextBlocksAsJson(@TempDir Path tempDir) throws Exception {
        Tool<ObjectNode, String> tool = new ToolBuilder<ObjectNode, String>()
            .name("fixture")
            .description("fixture")
            .call((_, _) -> "")
            .build();
        ToolResult original = new ToolResult(List.of(
            new TextBlock("a".repeat(30_000)),
            new TextBlock("b".repeat(30_000))), false)
            .withContentForm(ToolResultContentForm.BLOCKS);
        ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "session-1").workingDirectory(tempDir.toString()).build().withToolUseId("toolu-json");

        ToolResult processed = ToolResultStorage.process(original, tool, context,
            (_, _) -> tempDir.resolve("tool-results"));

        assertTrue(Files.exists(tempDir.resolve("tool-results/toolu-json.json")));
        String saved = Files.readString(tempDir.resolve("tool-results/toolu-json.json"));
        assertTrue(Strings.CS.contains(saved, "\"type\": \"text\""), saved);
        assertFalse(Strings.CS.contains(saved, "\"type\" :"), saved);
        assertTrue(Strings.CS.contains(((TextBlock) processed.content().getFirst()).text(), ".json"));
    }

    @Test
    void injectsMarkerForEmptyResult(@TempDir Path tempDir) {
        Tool<ObjectNode, String> tool = new ToolBuilder<ObjectNode, String>()
            .name("fixture")
            .description("fixture")
            .call((_, _) -> "")
            .build();
        ToolResult original = new ToolResult(List.of(new TextBlock("  ")), false);
        ToolExecutionContext context = ToolExecutionContext.builder(new AbortController(), "session-1").workingDirectory(tempDir.toString()).build().withToolUseId("toolu-empty");

        ToolResult processed = ToolResultStorage.process(original, tool, context,
            (_, _) -> tempDir.resolve("tool-results"));

        assertEquals("(fixture completed with no output)",
            ((TextBlock) processed.content().getFirst()).text());
    }

    @Test
    void growthBookPerToolOverrideWinsWithoutGlobalClamp() {
        ObjectNode features = new ObjectMapper().createObjectNode();
        features.putObject("tengu_velvet_ibis").put("fixture", 120_000);

        assertEquals(120_000,
            ToolResultStorage.effectiveThreshold(50_000, "fixture", features));
        assertEquals(50_000,
            ToolResultStorage.effectiveThreshold(50_000, "other", features));
        assertEquals(Long.MAX_VALUE,
            ToolResultStorage.effectiveThreshold(Integer.MAX_VALUE, "fixture", features));
    }

    @Test
    void declaredZeroAndNegativeFollowTsThresholdSemantics() {
        assertEquals(0, ToolResultStorage.effectiveThreshold(0, "fixture", null));
        assertEquals(-1, ToolResultStorage.effectiveThreshold(-1, "fixture", null));
    }

    @Test
    void oneTextBlockArrayPersistsAsJson(@TempDir Path tempDir) throws Exception {
        Tool<ObjectNode, String> tool = new ToolBuilder<ObjectNode, String>()
            .name("fixture").description("fixture").call((_, _) -> "").build();
        ToolResult original = ToolResult.success("x".repeat(60_000))
            .withContentForm(ToolResultContentForm.BLOCKS);
        ToolExecutionContext context = ToolExecutionContext.builder(
            new AbortController(), "session-1").workingDirectory(tempDir.toString())
            .build().withToolUseId("toolu-one-block");

        ToolResultStorage.process(original, tool, context,
            (_, _) -> tempDir.resolve("tool-results"));

        Path saved = tempDir.resolve("tool-results/toolu-one-block.json");
        assertTrue(Files.exists(saved));
        assertTrue(Strings.CS.startsWith(Files.readString(saved), "[\n  {\n"));
    }

    @Test
    void unsafeIdsAreHashedWithoutCollisions(@TempDir Path tempDir) {
        Path directory = tempDir.resolve("tool-results");
        Path slash = ToolResultStorage.safeOutputPath(directory, "abc/def", ".txt");
        Path question = ToolResultStorage.safeOutputPath(directory, "abc?def", ".txt");
        Path literal = ToolResultStorage.safeOutputPath(directory, "abc_def", ".txt");

        assertNotEquals(slash, question);
        assertNotEquals(slash, literal);
        assertNotEquals(question, literal);
        assertTrue(slash.startsWith(directory.toAbsolutePath().normalize()));
        assertEquals("abc_def.txt", literal.getFileName().toString());
    }
}
