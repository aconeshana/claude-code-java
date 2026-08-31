package com.claudecode.services.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HooksSettingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void emptySettingsReturnsEmptyMatchers() {
        assertEquals(List.of(), HooksSettings.EMPTY.getMatchers(HookEvent.PRE_TOOL_USE));
    }

    @Test
    void fromJsonWithNullReturnsEmpty() {
        assertEquals(HooksSettings.EMPTY, HooksSettings.fromJson(null));
    }

    @Test
    void fromJsonParsesCommandHook() {
        ObjectNode hooks = MAPPER.createObjectNode();
        ArrayNode preToolUse = hooks.putArray("pre_tool_use");

        ObjectNode matcher = preToolUse.addObject();
        matcher.put("matcher", "Bash");
        ArrayNode hooksList = matcher.putArray("hooks");
        ObjectNode hook = hooksList.addObject();
        hook.put("type", "command");
        hook.put("command", "echo check");

        HooksSettings settings = HooksSettings.fromJson(hooks);
        List<HookMatcher> matchers = settings.getMatchers(HookEvent.PRE_TOOL_USE);
        assertEquals(1, matchers.size());
        assertEquals("Bash", matchers.getFirst().matcher().orElse(""));
        assertEquals(1, matchers.getFirst().hooks().size());
        assertInstanceOf(BashCommandHook.class, matchers.getFirst().hooks().getFirst());
    }

    @Test
    void fromJsonParsesPromptHook() {
        ObjectNode hooks = MAPPER.createObjectNode();
        ArrayNode stop = hooks.putArray("stop");
        ObjectNode matcher = stop.addObject();
        ArrayNode hooksList = matcher.putArray("hooks");
        ObjectNode hook = hooksList.addObject();
        hook.put("type", "prompt");
        hook.put("prompt", "Review: $ARGUMENTS");
        hook.put("model", "haiku");

        HooksSettings settings = HooksSettings.fromJson(hooks);
        List<HookMatcher> matchers = settings.getMatchers(HookEvent.STOP);
        assertEquals(1, matchers.size());
        PromptHook ph = (PromptHook) matchers.getFirst().hooks().getFirst();
        assertEquals("Review: $ARGUMENTS", ph.prompt());
        assertEquals("haiku", ph.model().orElse(""));
    }

    @Test
    void fromJsonParsesHttpHook() {
        ObjectNode hooks = MAPPER.createObjectNode();
        ArrayNode sessionStart = hooks.putArray("session_start");
        ObjectNode matcher = sessionStart.addObject();
        ArrayNode hooksList = matcher.putArray("hooks");
        ObjectNode hook = hooksList.addObject();
        hook.put("type", "http");
        hook.put("url", "https://example.com/hook");
        hook.putObject("headers").put("Authorization", "Bearer $TOKEN");
        hook.putArray("allowedEnvVars").add("TOKEN");

        HooksSettings settings = HooksSettings.fromJson(hooks);
        List<HookMatcher> matchers = settings.getMatchers(HookEvent.SESSION_START);
        HttpHook hh = (HttpHook) matchers.getFirst().hooks().getFirst();
        assertEquals("https://example.com/hook", hh.url());
        assertEquals("Bearer $TOKEN", hh.headers().get("Authorization"));
        assertEquals(List.of("TOKEN"), hh.allowedEnvVars());
    }

    @Test
    void fromJsonParsesAgentHook() {
        ObjectNode hooks = MAPPER.createObjectNode();
        ArrayNode permReq = hooks.putArray("permission_request");
        ObjectNode matcher = permReq.addObject();
        ArrayNode hooksList = matcher.putArray("hooks");
        ObjectNode hook = hooksList.addObject();
        hook.put("type", "agent");
        hook.put("prompt", "Verify this");

        HooksSettings settings = HooksSettings.fromJson(hooks);
        AgentHook ah = (AgentHook) settings.getMatchers(HookEvent.PERMISSION_REQUEST)
            .getFirst().hooks().getFirst();
        assertEquals("Verify this", ah.prompt());
    }

    @Test
    void fromJsonParsesNew197EventNamesFromSettingsJson() {
        ObjectNode hooks = MAPPER.createObjectNode();
        for (String event : List.of("PostToolBatch", "UserPromptExpansion", "MessageDisplay")) {
            hooks.putArray(event).addObject().putArray("hooks").addObject()
                .put("type", "command")
                .put("command", "echo " + event);
        }

        HooksSettings settings = HooksSettings.fromJson(hooks);

        assertEquals("echo PostToolBatch", commandFor(settings, HookEvent.POST_TOOL_BATCH));
        assertEquals("echo UserPromptExpansion",
            commandFor(settings, HookEvent.USER_PROMPT_EXPANSION));
        assertEquals("echo MessageDisplay", commandFor(settings, HookEvent.MESSAGE_DISPLAY));
    }

    @Test
    void fromJsonSkipsUnknownEventTypes() {
        ObjectNode hooks = MAPPER.createObjectNode();
        hooks.putArray("unknown_event").addObject().putArray("hooks");

        HooksSettings settings = HooksSettings.fromJson(hooks);
        // Should not throw, just skip
        assertNotNull(settings);
    }

    @Test
    void fromJsonSkipsUnknownHookTypes() {
        ObjectNode hooks = MAPPER.createObjectNode();
        ArrayNode preToolUse = hooks.putArray("pre_tool_use");
        ObjectNode matcher = preToolUse.addObject();
        ArrayNode hooksList = matcher.putArray("hooks");
        ObjectNode hook = hooksList.addObject();
        hook.put("type", "unknown_type");
        hook.put("command", "echo test");

        HooksSettings settings = HooksSettings.fromJson(hooks);
        List<HookMatcher> matchers = settings.getMatchers(HookEvent.PRE_TOOL_USE);
        // Matcher exists but has no hooks (unknown type was skipped)
        assertTrue(matchers.isEmpty());
    }

    @Test
    void fromJsonParsesOnceAndAsyncFlags() {
        ObjectNode hooks = MAPPER.createObjectNode();
        ArrayNode preToolUse = hooks.putArray("pre_tool_use");
        ObjectNode matcher = preToolUse.addObject();
        ArrayNode hooksList = matcher.putArray("hooks");
        ObjectNode hook = hooksList.addObject();
        hook.put("type", "command");
        hook.put("command", "echo once");
        hook.put("once", true);
        hook.put("async", true);
        hook.put("asyncRewake", true);

        HooksSettings settings = HooksSettings.fromJson(hooks);
        BashCommandHook bch = (BashCommandHook) settings.getMatchers(HookEvent.PRE_TOOL_USE)
            .getFirst().hooks().getFirst();
        assertTrue(bch.once());
        assertTrue(bch.async());
        assertTrue(bch.asyncRewake());
    }

    @Test
    void fromYamlParsesSkillFrontmatterHookBody() {
        String yaml = """
            PreToolUse:
              - matcher: Bash
                hooks:
                  - type: command
                    command: echo skill-check
                    once: true
            """;

        HooksSettings settings = HooksSettings.fromYaml(yaml);

        BashCommandHook hook = (BashCommandHook) settings
            .getMatchers(HookEvent.PRE_TOOL_USE)
            .getFirst().hooks().getFirst();
        assertEquals("Bash", settings.getMatchers(HookEvent.PRE_TOOL_USE)
            .getFirst().matcher().orElseThrow());
        assertEquals("echo skill-check", hook.command());
        assertTrue(hook.once());
    }

    @Test
    void fromYamlReturnsEmptyForBlankOrMalformedInput() {
        assertEquals(HooksSettings.EMPTY, HooksSettings.fromYaml("  \n"));
        assertEquals(HooksSettings.EMPTY, HooksSettings.fromYaml("PreToolUse: ["));
    }

    private static String commandFor(HooksSettings settings, HookEvent event) {
        return ((BashCommandHook) settings.getMatchers(event)
            .getFirst().hooks().getFirst()).command();
    }
}
