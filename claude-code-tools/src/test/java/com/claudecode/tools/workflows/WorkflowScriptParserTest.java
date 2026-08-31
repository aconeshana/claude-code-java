package com.claudecode.tools.workflows;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowScriptParserTest {

    @Test
    void parsesFirstStatementLiteralMetadataAndReturnsBody() {
        String script = """
            // comments do not count as a statement
            export const meta = {
              name: "review-flow",
              title: `Review flow`,
              description: "Review a change in deterministic phases",
              whenToUse: "Use after implementation",
              phases: [
                { title: "Inspect", detail: "Read the diff", model: "sonnet" },
                { title: "Report" }
              ]
            };

            const result = await agent("Review the diff");
            return result;
            """;

        ParsedWorkflowScript parsed = WorkflowScriptParser.parse(script);

        assertEquals("review-flow", parsed.metadata().name());
        assertEquals("Review flow", parsed.metadata().title());
        assertEquals("Review a change in deterministic phases", parsed.metadata().description());
        assertEquals("Use after implementation", parsed.metadata().whenToUse());
        assertEquals(2, parsed.metadata().phases().size());
        assertEquals("Read the diff", parsed.metadata().phases().getFirst().detail());
        assertEquals("sonnet", parsed.metadata().phases().getFirst().model());
        assertTrue(Strings.CS.startsWith(parsed.body(), "const result = await agent"));
    }

    @Test
    void requiresMetaExportAsFirstStatement() {
        WorkflowScriptException error = assertThrows(WorkflowScriptException.class,
            () -> WorkflowScriptParser.parse("const before = 1; export const meta = { name: 'x', description: 'y' };"));

        assertEquals("`export const meta = { name, description, phases }` must be the FIRST statement in the script",
            error.getMessage());
    }

    @Test
    void rejectsNonLiteralMetadataAndReservedKeys() {
        assertThrows(WorkflowScriptException.class, () -> WorkflowScriptParser.parse("""
            export const meta = { name: getName(), description: "x" };
            """));
        assertThrows(WorkflowScriptException.class, () -> WorkflowScriptParser.parse("""
            export const meta = { name: "x", description: "y", __proto__: {} };
            """));
        assertThrows(WorkflowScriptException.class, () -> WorkflowScriptParser.parse("""
            export const meta = { name: "x", description: "y", phases: [...items] };
            """));
    }

    @Test
    void validatesRequiredMetadataAndPhaseShape() {
        assertThrows(WorkflowScriptException.class, () -> WorkflowScriptParser.parse("""
            export const meta = { name: " ", description: "y" };
            """));
        assertThrows(WorkflowScriptException.class, () -> WorkflowScriptParser.parse("""
            export const meta = { name: "x", description: "y", phases: [{ detail: "missing title" }] };
            """));
    }
}
