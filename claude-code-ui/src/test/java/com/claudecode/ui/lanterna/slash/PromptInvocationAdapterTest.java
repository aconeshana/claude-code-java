package com.claudecode.ui.lanterna.slash;

import com.claudecode.commands.CommandResult;
import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.services.hooks.BashCommandHook;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.HookEvent;
import com.claudecode.services.hooks.HookMatcher;
import com.claudecode.services.hooks.HooksSettings;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the prompt-command adapter between the command/UI and
 * front-end-neutral turn runtime. Structured content and turn-scoped state
 * must not be flattened to {@code CommandResult.output}.
 */
class PromptInvocationAdapterTest {

    @Test
    void dispatcherHandsTheOriginalStructuredInvocationToTheHost() {
        PromptInvocation invocation = promptInvocation();
        RecordingHost host = new RecordingHost();
        Map<Integer, PastedContent> pasted = Map.of(
            1, PastedContent.image(1, "abc", "image/png", null, null));

        SlashCommandDispatcher.submitPromptResult(
            host, "/mcp__server__diagram", CommandResult.forPrompt(invocation), pasted);

        assertEquals("/mcp__server__diagram", host.displayText);
        assertSame(invocation, host.invocation);
        assertSame(pasted, host.pasted);
    }

    @Test
    void busyTurnQueuesNonImmediateCommandsBeforeTheyAreExecuted() {
        Command normal = command(false);
        Command immediate = command(true);

        assertTrue(SlashCommandDispatcher.shouldQueueBeforeDispatch(normal, true));
        assertTrue(SlashCommandDispatcher.shouldQueueBeforeDispatch(null, true),
            "unknown slash input may resolve to a dynamic skill and must also wait");
        assertFalse(SlashCommandDispatcher.shouldQueueBeforeDispatch(immediate, true));
        assertFalse(SlashCommandDispatcher.shouldQueueBeforeDispatch(normal, false));
    }

    @Test
    void adapterPreservesBlocksAndTurnOverridesInUserInput() {
        PromptInvocation invocation = promptInvocation();
        Map<Integer, PastedContent> pasted = Map.of();

        UserInput input = PromptInvocationAdapter.toUserInput(
            "/plugin:review", invocation, pasted, "plan");

        assertEquals("/plugin:review", input.displayText());
        assertEquals("plan", input.permissionMode());
        assertTrue(input.isSlashCommand());
        assertEquals("loading review", input.progressMessage());
        assertEquals(List.of("Read", "Edit(src/**)"), input.allowedTools());
        assertEquals("claude-sonnet-4-5", input.modelOverride());
        assertEquals("medium", input.effortOverride());
        MessageContent content = (MessageContent) input.queryContent();
        assertEquals(invocation.content(), content.blocks());
    }

    @Test
    void adapterRegistersInvocationHooksAndCompactionRecordBeforeTurn() {
        PromptInvocation invocation = promptInvocation();
        RecordingHookEngine hooks = new RecordingHookEngine();
        InvokedSkillRegistry invokedSkills = new InvokedSkillRegistry();

        PromptInvocationAdapter.installTurnScopedState(
            invocation, "plugin:review", hooks,
            (name, path, content) -> invokedSkills.record(null, name, path, content));

        assertSame(invocation.hooks(), hooks.added);
        assertEquals(invocation.skillRoot(), hooks.skillRoot);
        InvokedSkillRegistry.Entry entry = invokedSkills.entriesFor(null).getFirst();
        assertEquals("plugin:review", entry.name());
        assertEquals("plugin:plugin:review", entry.path());
        assertEquals("review this image", entry.content());
    }

    private static PromptInvocation promptInvocation() {
        var imageSource = JsonNodeFactory.instance.objectNode()
            .put("type", "base64")
            .put("media_type", "image/png")
            .put("data", "abc");
        HooksSettings hooks = new HooksSettings(Map.of(
            HookEvent.USER_PROMPT_SUBMIT,
            List.of(new HookMatcher(Optional.empty(),
                List.of(new BashCommandHook("echo prompt"))))));
        return PromptInvocation.builder(List.of(
                new TextBlock("review this image"), new ImageBlock(imageSource)))
            .progressMessage("loading review")
            .allowedTools(List.of("Read", "Edit(src/**)"))
            .model("claude-sonnet-4-5")
            .effort("medium")
            .source("plugin")
            .loadedFrom("plugin")
            .hooks(hooks)
            .skillRoot(Path.of("/plugins/review"))
            .contentLength(17)
            .build();
    }

    private static Command command(boolean immediate) {
        return new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata(immediate ? "immediate" : "normal", "test");
            }
            @Override public boolean isImmediate() { return immediate; }
            @Override public CommandResult execute(CommandContext context, String args) {
                throw new AssertionError("queue decision must happen before command execution");
            }
        };
    }

    private static final class RecordingHookEngine extends HookEngine {
        private HooksSettings added;
        private Path skillRoot;

        RecordingHookEngine() {
            super(HooksSettings.EMPTY, null);
        }

        @Override
        public void addExtraHooks(HooksSettings extra, Path root) {
            this.added = extra;
            this.skillRoot = root;
        }
    }

    private static final class RecordingHost implements SlashHost {
        private String displayText;
        private PromptInvocation invocation;
        private Map<Integer, PastedContent> pasted;

        @Override public void handleQuery(String input) { }
        @Override public void executeQuery(String displayText, String queryContent,
                                           Map<Integer, PastedContent> pasted) { }
        @Override public void executePrompt(String displayText, PromptInvocation invocation,
                                            Map<Integer, PastedContent> pasted) {
            this.displayText = displayText;
            this.invocation = invocation;
            this.pasted = pasted;
        }
        @Override public void renderAndQueue(QueuedCommand cmd, String displayText) { }
        @Override public void showSessionPicker() { }
        @Override public void toggleFastMode() { }
        @Override public void stop() { }
        @Override public boolean isTurnInFlight() { return false; }
    }
}
