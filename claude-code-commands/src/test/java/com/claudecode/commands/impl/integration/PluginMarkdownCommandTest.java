package com.claudecode.commands.impl.integration;


import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandOutputChannel;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.CommandResultDisplay;
import com.claudecode.core.message.Usage;
import com.claudecode.runtime.plugins.PluginCommandDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginMarkdownCommandTest {

    private static PluginCommandDefinition def(String name, String prompt, List<String> argNames) {
        return PluginCommandDefinition.builder(name, prompt, "myplugin")
            .description("desc").argumentHint("[hint]").argNames(argNames)
            .hasUserSpecifiedDescription(true).build();
    }

    @Test
    void exposesMetadataFromDefinition() {
        PluginMarkdownCommand cmd = new PluginMarkdownCommand(PluginCommandDefinition
            .builder("myplugin:build", "content", "myplugin")
            .description("Build it").argumentHint("[target]")
            .argNames(List.of("source", "target")).hidden(true)
            .hasUserSpecifiedDescription(true).build());
        assertEquals("myplugin:build", cmd.name());
        assertEquals("Build it", cmd.description());
        assertEquals("[target]", cmd.argumentHint());
        assertEquals(List.of("source", "target"), cmd.argumentNames());
        assertTrue(cmd.isHidden());
        assertTrue(cmd.supportsNonInteractive(),
            "TS includes plugin prompt commands in commandsHeadless");
    }

    @Test
    void executeInjectsPromptAsQuery() {
        PluginMarkdownCommand cmd = new PluginMarkdownCommand(
            def("myplugin:x", "Do the thing", List.of()));
        CommandResult r = cmd.execute(CommandContext.minimal(), "");
        assertTrue(r.shouldQuery(), "plugin commands are TS type:'prompt'");
        assertEquals("Do the thing", r.output());
    }

    @Test
    void executeCarriesPluginPromptMetadataIntoInvocationEnvelope() {
        PluginCommandDefinition def = PluginCommandDefinition.builder("myplugin:x", "Do it", "myplugin")
            .description("desc")
            .allowedTools(List.of("Read", "Edit(~/.claude/settings.json)"))
            .model("sonnet").effort("medium").disableModelInvocation(true)
            .userFacingName("Friendly X").whenToUse("Use when X").version("1.2.3")
            .contentLength(5).hasUserSpecifiedDescription(true).build();

        CommandResult result = new PluginMarkdownCommand(def)
            .execute(CommandContext.minimal(), "");

        assertEquals(List.of("Read", "Edit(~/.claude/settings.json)"),
            result.promptInvocation().allowedTools());
        assertEquals("sonnet", result.promptInvocation().model());
        assertEquals("medium", result.promptInvocation().effort());
        assertTrue(result.promptInvocation().disableModelInvocation());
        assertEquals("Friendly X", result.promptInvocation().userFacingName());
        assertEquals("Use when X", result.promptInvocation().whenToUse());
        assertEquals("1.2.3", result.promptInvocation().version());
    }

    @Test
    void executeSubstitutesBareArguments() {
        PluginMarkdownCommand cmd = new PluginMarkdownCommand(
            def("myplugin:x", "Run with: $ARGUMENTS", List.of()));
        CommandResult r = cmd.execute(CommandContext.minimal(), "foo bar");
        assertEquals("Run with: foo bar", r.output());
    }

    @Test
    void executeSubstitutesNamedArguments() {
        PluginMarkdownCommand cmd = new PluginMarkdownCommand(
            def("myplugin:x", "from $src to $dst", List.of("src", "dst")));
        CommandResult r = cmd.execute(CommandContext.minimal(), "here there");
        assertEquals("from here to there", r.output());
    }

    @Test
    void executeAppendsArgumentsWhenNoPlaceholder() {
        PluginMarkdownCommand cmd = new PluginMarkdownCommand(
            def("myplugin:x", "Static prompt", List.of()));
        CommandResult r = cmd.execute(CommandContext.minimal(), "extra input");
        assertEquals("Static prompt\n\nARGUMENTS: extra input", r.output());
    }

    @Test
    void nullArgsBehaveLikeEmpty() {
        PluginMarkdownCommand cmd = new PluginMarkdownCommand(
            def("myplugin:x", "Static prompt", List.of()));
        CommandResult r = cmd.execute(CommandContext.minimal(), null);
        assertEquals("Static prompt", r.output());
        assertTrue(r.shouldQuery());
    }

    @Test
    void blankPromptRemainsAnEmptyPromptQuery() {
        PluginMarkdownCommand cmd = new PluginMarkdownCommand(
            def("myplugin:x", "", List.of()));
        CommandResult r = cmd.execute(CommandContext.minimal(), "");
        assertTrue(r.shouldQuery());
        assertEquals("", r.output());
    }

    @Test
    void substitutesSessionIdAtInvocationTime() {
        AtomicReference<String> sessionId = new AtomicReference<>("session-one");
        CommandContext context = CommandContext.builder(
                "model", List::of, () -> {}, _ -> {},
                () -> Usage.EMPTY,
                _ -> 0.0, System.getProperty("user.dir"), false)
            .currentSessionId(sessionId::get)
            .build();
        PluginMarkdownCommand cmd = new PluginMarkdownCommand(
            def("myplugin:x", "session=${CLAUDE_SESSION_ID}", List.of()));

        assertEquals("session=session-one", cmd.execute(context, "").output());
        sessionId.set("session-two");
        assertEquals("session=session-two", cmd.execute(context, "").output());
    }

    @Test
    void executesEmbeddedShellAfterArgumentAndSessionSubstitution() {
        AtomicReference<String> capturedText = new AtomicReference<>();
        AtomicReference<String> capturedName = new AtomicReference<>();
        AtomicReference<String> capturedShell = new AtomicReference<>();
        AtomicReference<List<String>> capturedTools = new AtomicReference<>();
        CommandContext context = CommandContext.builder(
                "model", List::of, () -> {}, _ -> {},
                () -> Usage.EMPTY,
                _ -> 0.0, System.getProperty("user.dir"), false)
            .currentSessionId(() -> "sess-9")
            .promptShellExecutor((text, name, allowedTools, shell) -> {
                capturedText.set(text);
                capturedName.set(name);
                capturedTools.set(allowedTools);
                capturedShell.set(shell);
                return text.replace("!`echo target sess-9`", "target sess-9");
            })
            .build();
        PluginCommandDefinition def = PluginCommandDefinition.builder("myplugin:x",
                "Result: !`echo $target ${CLAUDE_SESSION_ID}`", "myplugin")
            .description("desc").argNames(List.of("target"))
            .allowedTools(List.of("Bash(echo *)")).contentLength(46)
            .hasUserSpecifiedDescription(true).shell("powershell").build();

        CommandResult result = new PluginMarkdownCommand(def).execute(context, "target");

        assertEquals("Result: target sess-9", result.output());
        assertEquals("Result: !`echo target sess-9`", capturedText.get());
        assertEquals("/myplugin:x", capturedName.get());
        assertEquals(List.of("Bash(echo *)"), capturedTools.get());
        assertEquals("powershell", capturedShell.get());
    }

    @Test
    void embeddedShellMakesCommandLongRunning() {
        assertTrue(new PluginMarkdownCommand(
            def("myplugin:block", "before\n```!\necho hi\n```", List.of())).isLongRunning());
        assertTrue(new PluginMarkdownCommand(
            def("myplugin:inline", "before !`echo hi`", List.of())).isLongRunning());
        assertFalse(new PluginMarkdownCommand(
            def("myplugin:plain", "before `echo hi`", List.of())).isLongRunning());
    }

    @Test
    void missingShellExecutorCompletesOnStderrWithoutQuerying() {
        PluginMarkdownCommand command = new PluginMarkdownCommand(
            def("myplugin:shell", "before !`echo hi`", List.of()));

        CommandResult result = command.execute(CommandContext.minimal(), "");

        assertEquals("Shell command execution is unavailable for /myplugin:shell", result.output());
        assertEquals("", result.headlessOutput());
        assertEquals(CommandOutputChannel.STDERR, result.outputChannel());
        assertEquals(CommandResultDisplay.USER, result.display());
        assertFalse(result.shouldQuery());
    }

    @Test
    void shellExpansionFailureCompletesOnStderrWithoutQuerying() {
        CommandContext context = CommandContext.builder(
                "model", List::of, () -> {}, _ -> {},
                () -> Usage.EMPTY,
                _ -> 0.0, System.getProperty("user.dir"), false)
            .promptShellExecutor((_, _, _, _) -> {
                throw new IllegalStateException("shell denied");
            })
            .build();
        PluginMarkdownCommand command = new PluginMarkdownCommand(
            def("myplugin:shell", "before !`echo hi`", List.of()));

        CommandResult result = command.execute(context, "");

        assertEquals("shell denied", result.output());
        assertEquals(CommandOutputChannel.STDERR, result.outputChannel());
        assertEquals(CommandResultDisplay.USER, result.display());
        assertFalse(result.shouldQuery());
    }

    @Test
    void defaultVisibilityIsNotHidden() {
        PluginMarkdownCommand cmd = new PluginMarkdownCommand(
            def("myplugin:x", "p", List.of()));
        assertFalse(cmd.isHidden());
        assertNull(new PluginMarkdownCommand(PluginCommandDefinition.builder("a:b", "p", "a")
            .description("d").hasUserSpecifiedDescription(true).build()).argumentHint());
    }

}
