package com.claudecode.commands.impl.config;


import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.commands.testing.ProviderTestCommandPorts;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorCommandTest {

    @Test
    void color_noArgsListsChoices() {
        ColorCommand cmd = new ColorCommand();
        CommandResult r = cmd.execute(CommandContext.minimal(), "");
        assertTrue(Strings.CS.startsWith(r.output(), "Please provide a color"));
        for (String c : ColorCommand.AGENT_COLORS) assertTrue(Strings.CS.contains(r.output(), c));
    }

    @Test
    void color_setsValidColor(@TempDir Path dir) throws Exception {
        SessionManager sm = sessionManagerIn(dir);
        CommandContext ctx = ctxWithSession("session-1", sm);
        CommandResult r = new ColorCommand().execute(ctx, "cyan");
        assertEquals("Session color set to: cyan", r.output());
        String content = Files.readString(sm.getSessionFile("session-1"));
        assertTrue(Strings.CS.contains(content, "\"type\":\"agent-color\""), content);
        assertTrue(Strings.CS.contains(content, "\"agentColor\":\"cyan\""), content);
    }

    @Test
    void color_resetAliasWritesDefaultSentinel(@TempDir Path dir) throws Exception {
        SessionManager sm = sessionManagerIn(dir);
        CommandContext ctx = ctxWithSession("s1", sm);
        new ColorCommand().execute(ctx, "blue");
        CommandResult r = new ColorCommand().execute(ctx, "default");
        assertEquals("Session color reset to default", r.output());
        String content = Files.readString(sm.getSessionFile("s1"));
        assertTrue(Strings.CS.contains(content, "\"agentColor\":\"default\""), content);
        assertEquals("default", sm.readAgentColor("s1"));
    }

    @Test
    void color_rejectsUnknown() {
        CommandContext ctx = ctxWithSession("s1", null);
        CommandResult r = new ColorCommand().execute(ctx, "fuchsia");
        assertTrue(Strings.CS.startsWith(r.output(), "Invalid color"), r.output());
    }

    @Test
    void color_persistsEvenWithoutSession() {
        CommandResult r = new ColorCommand().execute(CommandContext.minimal(), "red");
        assertEquals("Session color set to: red", r.output());
    }

    @Test
    void color_callsLiveSetter(@TempDir Path dir) {
        SessionManager sm = sessionManagerIn(dir);
        AtomicReference<String> applied = new AtomicReference<>();
        CommandContext ctx = ctxWithSessionAndColorSetter("s2", sm, applied::set);
        new ColorCommand().execute(ctx, "orange");
        assertEquals("orange", applied.get(),
            "sessionColorSetter must be invoked so the prompt repaints immediately");
    }

    @Test
    void color_liveSetterReceivesDefaultOnReset(@TempDir Path dir) {
        SessionManager sm = sessionManagerIn(dir);
        AtomicReference<String> applied = new AtomicReference<>();
        CommandContext ctx = ctxWithSessionAndColorSetter("s3", sm, applied::set);
        new ColorCommand().execute(ctx, "reset");
        assertEquals("default", applied.get(),
            "reset alias must hand 'default' to the live setter (sentinel for UI)");
    }

    @Test
    void teammateCannotSetOwnColor() {
        CommandContext base = ctxWithSession("s1", null);
        CommandContext context = CommandContext.builder(
            base.session().model(), base.session().messagesSupplier(), base.session().clearMessages(),
            base.session().setModel(), base.session().usageSupplier(), base.session().costCalculator(),
            base.session().workingDirectory(), false)
            .currentSessionId(() -> "s1")
            .toolingCommands(ProviderTestCommandPorts.collaboration(true))
            .build();
        CommandResult result = new ColorCommand().execute(context, "cyan");

        assertEquals(
            "Cannot set color: This session is a swarm teammate. "
                + "Teammate colors are assigned by the team leader.",
            result.output());
    }

    private static SessionManager sessionManagerIn(Path dir) {
        return new SessionManager(dir, "/test/project");
    }

    private static CommandContext ctxWithSession(String id, SessionManager manager) {
        CommandContext.Builder builder = CommandContext.builder(
            "m", List::of, () -> {}, _ -> {},
            null, _ -> 0.0, ".", false)
            .currentSessionId(() -> id);
        if (manager != null) {
            builder.sessionCommands(ProviderTestCommandPorts.sessions(manager, new SessionStorage()));
        }
        return builder.build();
    }

    private static CommandContext ctxWithSessionAndColorSetter(
            String id, SessionManager manager, Consumer<String> setter) {
        return CommandContext.builder(
            "m", List::of, () -> {}, _ -> {},
            null, _ -> 0.0, ".", false)
            .currentSessionId(() -> id)
            .sessionCommands(ProviderTestCommandPorts.sessions(manager, new SessionStorage()))
            .sessionColorSetter(setter)
            .build();
    }
}
