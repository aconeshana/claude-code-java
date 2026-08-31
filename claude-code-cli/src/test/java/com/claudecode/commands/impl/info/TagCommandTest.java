package com.claudecode.commands.impl.info;


import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Usage;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.commands.testing.ProviderTestCommandPorts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagCommandTest {

    private static final String HELP = """
        Usage: /tag <tag-name>

        Toggle a searchable tag on the current session.
        Run the same command again to remove the tag.
        Tags are displayed after the branch name in /resume and can be searched with /.

        Examples:
          /tag bugfix        # Add tag
          /tag bugfix        # Remove tag (toggle)
          /tag feature-auth
          /tag wip""";

    @TempDir
    Path baseDir;

    @Test
    void noArgsAndCommonHelpArgsShowOriginalHelpBeforeSessionValidation() {
        TagCommand command = command();

        assertEquals(HELP, command.execute(CommandContext.minimal(), "").output());
        assertEquals(HELP, command.execute(CommandContext.minimal(), "--help").output());
        assertEquals(HELP, command.execute(CommandContext.minimal(), "?").output());
    }

    @Test
    void normalizationMatchesRecursiveUnicodeSanitizationAndTrimOnly() {
        assertEquals("#Feature 修复", TagCommand.normalizeTag("  #Ｆｅａｔ​ure 修复  "));
        assertEquals("", TagCommand.normalizeTag("\uE000​"));
    }

    @Test
    void differentTagIsSetWithoutInventedHashOrWhitespaceRewrites() {
        SessionManager manager = manager();
        String sessionId = manager.createSession();

        CommandResult result = command().execute(context(sessionId, manager, null), " #bug fix ");

        assertEquals("Tagged session with ##bug fix", result.output());
        assertEquals("#bug fix",
            new SessionStorage().scanMetadata(manager.getSessionFile(sessionId)).tag().orElseThrow());
    }

    @Test
    void sameTagLaunchesConfirmationAndOnlyConfirmedActionRemovesIt() {
        SessionManager manager = manager();
        String sessionId = manager.createSession();
        TagCommand command = command();
        command.execute(context(sessionId, manager, null), "wip");
        AtomicReference<CommandContext.TagRemovalRequest> request = new AtomicReference<>();

        CommandResult pending = command.execute(context(sessionId, manager, request::set), "wip");

        assertTrue(pending.silent());
        assertNotNull(request.get());
        assertEquals("wip", request.get().tagName());
        assertEquals("wip",
            new SessionStorage().scanMetadata(manager.getSessionFile(sessionId)).tag().orElseThrow());

        CommandResult cancelled = request.get().cancel().get();
        assertEquals("Kept tag #wip", cancelled.output());
        assertEquals("wip",
            new SessionStorage().scanMetadata(manager.getSessionFile(sessionId)).tag().orElseThrow());

        CommandResult confirmed = request.get().confirm().get();
        assertEquals("Removed tag #wip", confirmed.output());
        assertTrue(new SessionStorage().scanMetadata(manager.getSessionFile(sessionId))
            .tag().orElseThrow().isEmpty());
    }

    private TagCommand command() {
        return new TagCommand();
    }

    private SessionManager manager() {
        return new SessionManager(baseDir, "/test/project");
    }

    private CommandContext context(String sessionId, SessionManager manager,
                                   Consumer<CommandContext.TagRemovalRequest> launcher) {
        CommandContext.Builder builder = CommandContext.builder(
            "model", List::of, () -> {}, _ -> {}, () -> Usage.EMPTY,
            _ -> 0.0, "/test/project", false)
            .currentSessionId(() -> sessionId)
            .sessionCommands(ProviderTestCommandPorts.sessions(manager, new SessionStorage()));
        if (launcher != null) builder.tagRemovalLauncher(launcher);
        return builder.build();
    }
}
