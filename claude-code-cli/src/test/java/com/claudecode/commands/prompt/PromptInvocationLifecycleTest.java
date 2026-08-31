package com.claudecode.commands.prompt;


import com.claudecode.core.message.TextBlock;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.services.hooks.BashCommandHook;
import com.claudecode.services.hooks.HookEvent;
import com.claudecode.services.hooks.HookMatcher;
import com.claudecode.services.hooks.HooksSettings;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PromptInvocationLifecycleTest {

    @Test
    void installRegistersHooksWithSkillRootAndRecordsResolvedInvocation() {
        HooksSettings settings = new HooksSettings(Map.of(
            HookEvent.USER_PROMPT_SUBMIT,
            List.of(new HookMatcher(Optional.empty(),
                List.of(new BashCommandHook("${CLAUDE_PLUGIN_ROOT}/check.sh"))))));
        Path skillRoot = Path.of("/plugins/review");
        PromptInvocation invocation = PromptInvocation.builder(
                List.of(new TextBlock("resolved review body")))
            .source("plugin")
            .hooks(settings)
            .skillRoot(skillRoot)
            .build();
        RecordingHookEngine hooks = new RecordingHookEngine();
        InvokedSkillRegistry registry = new InvokedSkillRegistry();

        PromptInvocationLifecycle.install(
            invocation, "plugin:review", hooks,
            (name, path, content) -> registry.record(null, name, path, content));

        assertSame(settings, hooks.added);
        assertEquals(skillRoot, hooks.skillRoot);
        InvokedSkillRegistry.Entry entry = registry.entriesFor(null).getFirst();
        assertEquals("plugin:review", entry.name());
        assertEquals("plugin:plugin:review", entry.path());
        assertEquals("resolved review body", entry.content());
    }

    @Test
    void clearRemovesTurnScopedHooks() {
        RecordingHookEngine hooks = new RecordingHookEngine();

        PromptInvocationLifecycle.clear(hooks);

        assertEquals(1, hooks.clears);
    }

    @Test
    void commandNameIsDerivedFromTheQualifiedSlashInputWithoutArguments() {
        assertEquals("plugin:review",
            PromptInvocationLifecycle.commandNameFromInput(" /plugin:review src/Main.java "));
        assertEquals("mcp__vision__inspect",
            PromptInvocationLifecycle.commandNameFromInput("/mcp__vision__inspect"));
        assertNull(PromptInvocationLifecycle.commandNameFromInput("   "));
    }

    @Test
    void installDispatchesReleasedUserPromptExpansionMetadata() {
        RecordingHookEngine hooks = new RecordingHookEngine();
        PromptInvocation invocation = PromptInvocation.builder(
                List.of(new TextBlock("expanded")))
            .source("plugin")
            .build();

        PromptInvocationLifecycle.install(invocation,
            "/plugin:review src/Main.java", "plugin:review", hooks, null);

        assertEquals("slash_command", hooks.expansionType);
        assertEquals("plugin:review", hooks.commandName);
        assertEquals("src/Main.java", hooks.commandArgs);
        assertEquals("plugin", hooks.commandSource);
        assertEquals("/plugin:review src/Main.java", hooks.originalPrompt);
    }

    private static final class RecordingHookEngine implements HookDispatcher {
        private HookDispatcher.InvocationHooks added;
        private Path skillRoot;
        private int clears;
        private String expansionType;
        private String commandName;
        private String commandArgs;
        private String commandSource;
        private String originalPrompt;

        @Override
        public void installInvocationHooks(HookDispatcher.InvocationHooks extra, Path root) {
            this.added = extra;
            this.skillRoot = root;
        }

        @Override
        public void clearInvocationHooks() {
            clears++;
        }

        @Override
        public HookOutcome dispatchUserPromptExpansionWithOutcome(
                String expansionType, String commandName, String commandArgs,
                String commandSource, String originalPrompt) {
            this.expansionType = expansionType;
            this.commandName = commandName;
            this.commandArgs = commandArgs;
            this.commandSource = commandSource;
            this.originalPrompt = originalPrompt;
            return HookOutcome.PROCEED;
        }

        @Override public boolean dispatchPreToolUse(String toolName, JsonNode input, String toolUseId) { return true; }
        @Override public void dispatchPostToolUse(String toolName, JsonNode input, JsonNode output, String toolUseId) { }
        @Override public void dispatchUserPromptSubmit(String prompt) { }
        @Override public void dispatchSessionStart(String trigger) { }
        @Override public void dispatchStop(String reason) { }
        @Override public void dispatchSessionEnd(String reason) { }
    }
}
