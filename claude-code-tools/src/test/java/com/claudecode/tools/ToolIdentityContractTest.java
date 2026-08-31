package com.claudecode.tools;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.commons.lang3.Strings;

class ToolIdentityContractTest {

    @Test
    void annotatedToolReadsAndCachesClassLevelIdentity() {
        Tool<?, ?> tool = new StaticTestTool();

        assertEquals("static-test", tool.name());
        assertEquals(List.of("legacy", "old-static"), tool.aliases());
        assertTrue(tool.shouldDefer());
        assertTrue(tool.strict());
        assertEquals(20_000, tool.maxResultSizeChars());
        assertTrue(tool.isReadOnly());
        assertTrue(tool.isConcurrencySafe());
        assertSame(tool.identity(), tool.identity());
    }

    @Test
    void annotatedToolDefaultsToImmutableEmptyAliases() {
        Tool<?, ?> tool = new NoAliasTestTool();

        assertEquals(List.of(), tool.aliases());
        assertFalse(tool.shouldDefer());
        assertFalse(tool.strict());
        assertEquals(100_000, tool.maxResultSizeChars());
        assertFalse(tool.isReadOnly());
        assertFalse(tool.isConcurrencySafe());
        assertThrows(UnsupportedOperationException.class,
            () -> tool.aliases().add("unexpected"));
    }

    @Test
    void inputAwareBehaviorCanSpecializeClassLevelDefaults() {
        InputAwareTestTool tool = new InputAwareTestTool();
        JsonNode read = JsonUtils.getMapper().createObjectNode().put("read", true);
        JsonNode write = JsonUtils.getMapper().createObjectNode().put("read", false);

        assertTrue(tool.isReadOnly());
        assertTrue(tool.isConcurrencySafe());
        assertTrue(tool.isReadOnly(read));
        assertTrue(tool.isConcurrencySafe(read));
        assertFalse(tool.isReadOnly(write));
        assertFalse(tool.isConcurrencySafe(write));
    }

    @Test
    void annotatedToolFailsClearlyWhenAnnotationIsMissing() {
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> new MissingAnnotationTool().identity());

        assertTrue(Strings.CS.contains(error.getMessage(), MissingAnnotationTool.class.getName()));
        assertTrue(Strings.CS.contains(error.getMessage(), "@BuiltInTool"));
    }

    @Test
    void identityRejectsInvalidNamesAndAliasesAndCopiesTheList() {
        assertThrows(NullPointerException.class, () -> new ToolIdentity(null));
        assertThrows(IllegalArgumentException.class, () -> new ToolIdentity(" "));
        assertThrows(NullPointerException.class, () -> new ToolIdentity("tool", null));
        assertThrows(NullPointerException.class,
            () -> new ToolIdentity("tool", Arrays.asList("ok", null)));
        assertThrows(IllegalArgumentException.class,
            () -> new ToolIdentity("tool", List.of(" ")));

        List<String> aliases = new ArrayList<>(List.of("legacy"));
        ToolIdentity identity = new ToolIdentity("tool", aliases);
        aliases.add("later");

        assertEquals(List.of("legacy"), identity.aliases());
        assertThrows(UnsupportedOperationException.class,
            () -> identity.aliases().add("unexpected"));
    }

    @Test
    void dynamicToolProvidesInstanceIdentity() {
        Tool<?, ?> first = new DynamicTestTool("first");
        Tool<?, ?> second = new DynamicTestTool("second");

        assertEquals("first", first.name());
        assertEquals("second", second.name());
    }

    @BuiltInTool(
        name = "static-test",
        aliases = {"legacy", "old-static"},
        shouldDefer = true,
        strict = true,
        maxResultSizeChars = 20_000,
        readOnly = true,
        concurrencySafe = true
    )
    private static final class StaticTestTool extends AnnotatedTool<JsonNode, String> {
        @Override public String description() { return "test"; }
        @Override public JsonNode inputSchema() { return mapper().createObjectNode(); }
        @Override public String call(JsonNode input, ToolExecutionContext context) { return "ok"; }
    }

    @BuiltInTool(name = "no-alias")
    private static final class NoAliasTestTool extends AnnotatedTool<JsonNode, String> {
        @Override public String description() { return "test"; }
        @Override public JsonNode inputSchema() { return mapper().createObjectNode(); }
        @Override public String call(JsonNode input, ToolExecutionContext context) { return "ok"; }
    }

    @BuiltInTool(name = "input-aware", readOnly = true, concurrencySafe = true)
    private static final class InputAwareTestTool extends AnnotatedTool<JsonNode, String> {
        @Override public String description() { return "test"; }
        @Override public JsonNode inputSchema() { return mapper().createObjectNode(); }
        @Override public String call(JsonNode input, ToolExecutionContext context) { return "ok"; }
        @Override public boolean isReadOnly(JsonNode input) { return input.path("read").asBoolean(); }
        @Override public boolean isConcurrencySafe(JsonNode input) { return isReadOnly(input); }
    }

    private static final class MissingAnnotationTool extends AnnotatedTool<JsonNode, String> {
        @Override public String description() { return "test"; }
        @Override public JsonNode inputSchema() { return mapper().createObjectNode(); }
        @Override public String call(JsonNode input, ToolExecutionContext context) { return "ok"; }
    }

    private static final class DynamicTestTool extends Tool<JsonNode, String> {
        private final ToolIdentity identity;

        private DynamicTestTool(String name) {
            this.identity = new ToolIdentity(name);
        }

        @Override public ToolIdentity identity() { return identity; }
        @Override public String description() { return "test"; }
        @Override public JsonNode inputSchema() { return mapper().createObjectNode(); }
        @Override public String call(JsonNode input, ToolExecutionContext context) { return "ok"; }
    }
}
