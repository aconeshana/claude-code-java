package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentGenerator}.
 */
class AgentGeneratorTest {

    @Test
    void buildPrompt_includesUserDescription() {
        String prompt = AgentGenerator.buildPrompt("a code reviewer", List.of());
        assertTrue(Strings.CS.contains(prompt, "Create an agent configuration based on this request: \"a code reviewer\"."));
        assertTrue(Strings.CS.contains(prompt, "Return ONLY the JSON object, no other text."));
    }

    @Test
    void buildPrompt_includesExistingIdentifierExclusionList() {
        String prompt = AgentGenerator.buildPrompt("a code reviewer", List.of("code-reviewer", "test-runner"));
        assertTrue(Strings.CS.contains(prompt, "must NOT be used: code-reviewer, test-runner"), prompt);
    }

    @Test
    void buildPrompt_noExistingIdentifiers_omitsExclusionList() {
        String prompt = AgentGenerator.buildPrompt("x", List.of());
        assertFalse(Strings.CS.contains(prompt, "must NOT be used"));
    }

    @Test
    void parseResponse_directJson() {
        var result = AgentGenerator.parseResponse(
            "{\"identifier\":\"code-reviewer\",\"whenToUse\":\"Use when reviewing code\",\"systemPrompt\":\"You are a reviewer.\"}");
        assertEquals("code-reviewer", result.identifier());
        assertEquals("Use when reviewing code", result.whenToUse());
        assertEquals("You are a reviewer.", result.systemPrompt());
    }

    @Test
    void parseResponse_extractsJsonFromSurroundingText() {
        var result = AgentGenerator.parseResponse(
            "Here is the agent:\n\n{\"identifier\":\"a\",\"whenToUse\":\"b\",\"systemPrompt\":\"c\"}\n\nHope that helps!");
        assertEquals("a", result.identifier());
    }

    @Test
    void parseResponse_missingField_throws() {
        assertThrows(AgentGenerator.AgentGenerationException.class, () ->
            AgentGenerator.parseResponse("{\"identifier\":\"a\",\"whenToUse\":\"b\"}"));
    }

    @Test
    void parseResponse_malformedJson_throws() {
        assertThrows(AgentGenerator.AgentGenerationException.class, () ->
            AgentGenerator.parseResponse("this is not json at all"));
    }

    @Test
    void parseResponse_blankFields_throws() {
        assertThrows(AgentGenerator.AgentGenerationException.class, () ->
            AgentGenerator.parseResponse("{\"identifier\":\"\",\"whenToUse\":\"b\",\"systemPrompt\":\"c\"}"));
    }
}
