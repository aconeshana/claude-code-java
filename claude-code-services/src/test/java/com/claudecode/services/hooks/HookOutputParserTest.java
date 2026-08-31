package com.claudecode.services.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Characterizes stateless hook-output parsing before extraction from {@link HookEngine}.
 */
class HookOutputParserTest {

    @Test
    void parsesGenericDecisionsAndFallsBackToTheFirstJsonLine() {
        HookOutputParser parser = new HookOutputParser();

        HookResult prevent = parser.parse(
            "{\"continue\":false,\"stopReason\":\"finished\"}");
        assertEquals("finished", assertInstanceOf(HookResult.PreventContinuation.class, prevent)
            .stopReason().orElseThrow());

        HookResult context = parser.parse(
            "{\"hookSpecificOutput\":{\"additionalContext\":\"remember this\"}}");
        assertEquals("remember this", assertInstanceOf(HookResult.Allow.class, context)
            .additionalContext().orElseThrow());

        HookResult.Decorated trailing = assertInstanceOf(HookResult.Decorated.class,
            parser.parse("{\"decision\":\"block\",\"reason\":\"no\"}\nnot json"));
        assertEquals("no", assertInstanceOf(HookResult.Block.class, trailing.result()).reason());
        assertEquals("not json", trailing.effects().successOutput().orElseThrow());
    }

    @Test
    void validatesTheStrictPromptHookContractWithoutProducingAttachments() {
        HookOutputParser parser = new HookOutputParser();

        HookOutputParser.PromptDecision allowed = parser.parsePromptDecision("{\"ok\":true}");
        assertTrue(allowed.valid());
        assertTrue(allowed.allowed());

        HookOutputParser.PromptDecision blocked = parser.parsePromptDecision(
            "{\"ok\":false,\"reason\":\"unsafe\"}");
        assertTrue(blocked.valid());
        assertEquals("unsafe", blocked.reason());

        HookOutputParser.PromptDecision malformed = parser.parsePromptDecision(
            "{\"ok\":true,\"unexpected\":1}");
        assertFalse(malformed.valid());
        assertEquals("Schema validation failed: unexpected field: unexpected", malformed.failure());
    }

    @Test
    void stripsReleasedMarkdownFencesBeforeParsingJson() {
        HookOutputParser parser = new HookOutputParser();

        HookOutputParser.PromptDecision blocked = parser.parsePromptDecision(
            "  ```JSON\n{\"ok\":false,\"reason\":\"not done\"}\n```  ");

        assertTrue(blocked.valid());
        assertFalse(blocked.allowed());
        assertEquals("not done", blocked.reason());
    }

    @Test
    void preservesValidatedHookSpecificOutputForItsMatchingEvent() {
        HookOutputParser parser = new HookOutputParser();

        HookResult.Structured result = assertInstanceOf(HookResult.Structured.class,
            parser.parse("""
                {"hookSpecificOutput":{"hookEventName":"PreToolUse",
                 "permissionDecision":"allow","updatedInput":{"path":"fixed"},
                 "additionalContext":"checked"}}
                """, HookEvent.PRE_TOOL_USE));

        assertEquals("fixed", result.output().path("updatedInput").path("path").asText());
        assertEquals("checked", result.additionalContext().orElseThrow());
        assertInstanceOf(HookResult.Skip.class, parser.parse("""
            {"hookSpecificOutput":{"hookEventName":"PostToolUse",
             "additionalContext":"wrong event"}}
            """, HookEvent.PRE_TOOL_USE));
    }

    @Test
    void preservesGenericEffectsAndSessionStartFieldsWithoutInjectingThemAsContext() {
        HookOutputParser parser = new HookOutputParser();
        ObjectNode output = JsonUtils.getMapper().createObjectNode();
        output.put("systemMessage", "configuration refreshed");
        output.put("suppressOutput", true);
        output.put("terminalSequence", "\u001b]0;Hook title\u0007");
        ObjectNode specific = output.putObject("hookSpecificOutput");
        specific.put("hookEventName", "SessionStart");
        specific.put("additionalContext", "model context");
        specific.put("sessionTitle", "Hook session");
        specific.put("reloadSkills", true);
        specific.putArray("watchPaths").add("/tmp/a").add("/tmp/b");

        HookResult.Decorated decorated = assertInstanceOf(HookResult.Decorated.class,
            parser.parse(output.toString(), HookEvent.SESSION_START));

        HookResult.Structured structured = assertInstanceOf(
            HookResult.Structured.class, decorated.result());
        assertEquals("model context", structured.additionalContext().orElseThrow());
        assertEquals("Hook session", structured.output().path("sessionTitle").asText());
        assertTrue(structured.output().path("reloadSkills").asBoolean());
        assertEquals(2, structured.output().path("watchPaths").size());
        assertEquals("configuration refreshed",
            decorated.effects().systemMessage().orElseThrow());
        assertTrue(decorated.effects().suppressOutput());
        assertEquals("\u001b]0;Hook title\u0007",
            decorated.effects().terminalSequence().orElseThrow());
        assertNull(decorated.effects().validationError());
    }

    @Test
    void rejectsUnsafeTerminalSequencesWithoutDiscardingOtherHookOutput() {
        HookOutputParser parser = new HookOutputParser();
        ObjectNode output = JsonUtils.getMapper().createObjectNode();
        output.put("systemMessage", "still visible");
        output.put("terminalSequence", "\u001b]52;c;clipboard\u0007");

        HookResult.Decorated decorated = assertInstanceOf(HookResult.Decorated.class,
            parser.parse(output.toString(), HookEvent.STOP));

        assertInstanceOf(HookResult.Allow.class, decorated.result());
        assertEquals("still visible", decorated.effects().systemMessage().orElseThrow());
        assertTrue(decorated.effects().terminalSequence().isEmpty());
        assertTrue(decorated.effects().validationError().contains("terminalSequence"));
    }

    @Test
    void diagnosesInvalidSessionAndWatchEffectTypes() {
        HookOutputParser parser = new HookOutputParser();

        HookResult.Decorated decorated = assertInstanceOf(HookResult.Decorated.class,
            parser.parse("""
                {"hookSpecificOutput":{"hookEventName":"SessionStart",
                 "sessionTitle":7,"reloadSkills":"yes","watchPaths":["/tmp/a",9]}}
                """, HookEvent.SESSION_START));

        assertTrue(decorated.effects().validationError().contains(
            "sessionTitle must be a string"));
        assertTrue(decorated.effects().validationError().contains(
            "reloadSkills must be a boolean"));
        assertTrue(decorated.effects().validationError().contains(
            "watchPaths entries must be strings"));

        HookResult.Decorated cwd = assertInstanceOf(HookResult.Decorated.class,
            parser.parse("""
                {"hookSpecificOutput":{"hookEventName":"CwdChanged","watchPaths":"/tmp/a"}}
                """, HookEvent.CWD_CHANGED));
        assertTrue(cwd.effects().validationError().contains("watchPaths must be an array"));
    }

    @Test
    void eventNameMismatchDiscardsAllGenericEffects() {
        HookOutputParser parser = new HookOutputParser();
        ObjectNode output = JsonUtils.getMapper().createObjectNode();
        output.put("systemMessage", "must not display");
        output.put("terminalSequence", "\u001b]0;bad\u0007");
        output.putObject("hookSpecificOutput").put("hookEventName", "PostToolUse");

        assertInstanceOf(HookResult.Skip.class,
            parser.parse(output.toString(), HookEvent.PRE_TOOL_USE));
    }

    @Test
    void terminalSequenceAllowlistAcceptsProgressAndRejectsUnsafeCompositions() {
        assertNull(HookOutputParser.terminalSequenceRejection("\u001b]9;4;1;50\u0007"));
        assertNull(HookOutputParser.terminalSequenceRejection("\u001b]777;notify\u001b\\"));
        assertNull(HookOutputParser.terminalSequenceRejection("\u0007"));

        assertNotNull(HookOutputParser.terminalSequenceRejection("prefix\u001b]0;x\u0007"));
        assertNotNull(HookOutputParser.terminalSequenceRejection("\u001b]9;3;50\u0007"));
        assertNotNull(HookOutputParser.terminalSequenceRejection("\u001b]9;4;1;101\u0007"));
        assertNotNull(HookOutputParser.terminalSequenceRejection("\u001b]0;bad\u001bX\u0007"));
        assertNotNull(HookOutputParser.terminalSequenceRejection("\u001b]0;bad\u009b\u0007"));
        assertNotNull(HookOutputParser.terminalSequenceRejection("\u001b]0;missing"));
    }

    @Test
    void suppressOutputOnlyControlsTrailingSuccessfulPlainText() {
        HookOutputParser parser = new HookOutputParser();

        HookResult.Decorated visible = assertInstanceOf(HookResult.Decorated.class,
            parser.parse("{\"suppressOutput\":false}\nvisible success output", HookEvent.STOP));
        assertEquals("visible success output", visible.effects().successOutput().orElseThrow());

        HookResult.Decorated hidden = assertInstanceOf(HookResult.Decorated.class,
            parser.parse("{\"suppressOutput\":true}\nhidden success output", HookEvent.STOP));
        assertTrue(hidden.effects().successOutput().isEmpty());
        assertTrue(hidden.effects().suppressOutput());
    }
}
