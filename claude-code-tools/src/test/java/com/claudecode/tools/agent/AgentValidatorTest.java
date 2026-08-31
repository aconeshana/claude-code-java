package com.claudecode.tools.agent;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.agent.AgentSource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentValidator}.
 */
class AgentValidatorTest {

    private static final Set<String> TOOLS = Set.of("Read", "Bash", "Edit");

    // ── validateAgentType ────────────────────────────────────────────────────

    @Test
    void validateAgentType_empty_isRequired() {
        assertEquals("Agent type is required", AgentValidator.validateAgentType(""));
        assertEquals("Agent type is required", AgentValidator.validateAgentType(null));
    }

    @Test
    void validateAgentType_invalidCharacters_rejected() {
        String msg = AgentValidator.validateAgentType("bad_name!");
        assertNotNull(msg);
        assertTrue(Strings.CS.contains(msg, "alphanumeric"), msg);
    }

    @Test
    void validateAgentType_startsOrEndsWithHyphen_rejected() {
        assertNotNull(AgentValidator.validateAgentType("-bad"));
        assertNotNull(AgentValidator.validateAgentType("bad-"));
    }

    @Test
    void validateAgentType_tooShort_rejected() {
        assertNotNull(AgentValidator.validateAgentType("ab"));
    }

    @Test
    void validateAgentType_tooLong_rejected() {
        assertNotNull(AgentValidator.validateAgentType("a".repeat(51)));
    }

    @Test
    void validateAgentType_validName_returnsNull() {
        assertNull(AgentValidator.validateAgentType("code-reviewer"));
        assertNull(AgentValidator.validateAgentType("abc"));
    }

    // ── validate ─────────────────────────────────────────────────────────────

    @Test
    void validate_duplicateInDifferentSource_isError() {
        var existing = BuiltInAgentDefinitions.AgentDefinition.builder("reviewer", "existing")
            .tools(List.of("*")).source(AgentSource.USER).build();
        var result = AgentValidator.validate("reviewer", AgentSource.PROJECT, "A good enough description",
            List.of("Read"), "A system prompt that is definitely long enough.", TOOLS, List.of(existing));
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> Strings.CS.contains(e, "already exists")), result.errors().toString());
    }

    @Test
    void validate_sameNameSameSource_isNotError() {
        // Self-edit case: an agent being edited shares its own name+source.
        var self = BuiltInAgentDefinitions.AgentDefinition.builder("reviewer", "existing")
            .tools(List.of("*")).source(AgentSource.PROJECT).build();
        var result = AgentValidator.validate("reviewer", AgentSource.PROJECT, "A good enough description",
            List.of("Read"), "A system prompt that is definitely long enough.", TOOLS, List.of(self));
        assertTrue(result.isValid(), result.errors().toString());
    }

    @Test
    void validate_shortSystemPrompt_isError() {
        var result = AgentValidator.validate("agent-x", AgentSource.PROJECT, "A good enough description",
            List.of("Read"), "too short", TOOLS, List.of());
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> Strings.CS.contains(e, "too short")), result.errors().toString());
    }

    @Test
    void validate_missingSystemPrompt_isError() {
        var result = AgentValidator.validate("agent-x", AgentSource.PROJECT, "A good enough description",
            List.of("Read"), "", TOOLS, List.of());
        assertFalse(result.isValid());
        assertTrue(result.errors().contains("System prompt is required"));
    }

    @Test
    void validate_emptyOrNullTools_warningsOnly() {
        var withNull = AgentValidator.validate("agent-x", AgentSource.PROJECT, "A good enough description",
            null, "A system prompt that is definitely long enough.", TOOLS, List.of());
        assertTrue(withNull.isValid());
        assertTrue(withNull.warnings().stream().anyMatch(w -> Strings.CS.contains(w, "all tools")));

        var withEmpty = AgentValidator.validate("agent-x", AgentSource.PROJECT, "A good enough description",
            List.of(), "A system prompt that is definitely long enough.", TOOLS, List.of());
        assertTrue(withEmpty.isValid());
        assertTrue(withEmpty.warnings().stream().anyMatch(w -> Strings.CS.contains(w, "very limited")));
    }

    @Test
    void validate_invalidTools_isError() {
        var result = AgentValidator.validate("agent-x", AgentSource.PROJECT, "A good enough description",
            List.of("Read", "NoSuchTool"), "A system prompt that is definitely long enough.", TOOLS, List.of());
        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(e -> Strings.CS.contains(e, "NoSuchTool")), result.errors().toString());
    }

    @Test
    void validate_shortDescription_isWarningNotError() {
        var result = AgentValidator.validate("agent-x", AgentSource.PROJECT, "short",
            List.of("Read"), "A system prompt that is definitely long enough.", TOOLS, List.of());
        assertTrue(result.isValid());
        assertTrue(result.warnings().stream().anyMatch(w -> Strings.CS.contains(w, "more descriptive")));
    }

    @Test
    void validate_missingDescription_isError() {
        var result = AgentValidator.validate("agent-x", AgentSource.PROJECT, "",
            List.of("Read"), "A system prompt that is definitely long enough.", TOOLS, List.of());
        assertFalse(result.isValid());
        assertTrue(result.errors().contains("Description (description) is required"));
    }

    @Test
    void validate_allFieldsValid_isValidWithNoWarnings() {
        var result = AgentValidator.validate("agent-x", AgentSource.PROJECT,
            "Use this agent when you need code review", List.of("Read", "Bash"),
            "A system prompt that is definitely long enough to pass validation.", TOOLS, List.of());
        assertTrue(result.isValid());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
    }
}
