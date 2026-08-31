package com.claudecode.commands.impl.info;

import com.claudecode.commands.impl.session.ExitCommand;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.bootstrap.CommandFactory;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.CommandResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class StubCommandHiddenTest {

    @Test
    void stubCommand_isHidden() {
        assertTrue(new StubCommand("foo", "desc").isHidden(),
            "StubCommand must be hidden so /help and suggestions skip it");
    }

    @Test
    void nonStubCommand_isNotHidden() {
        assertFalse(new ExitCommand().isHidden(),
            "Regular commands should default to isHidden() == false");
    }

    @Test
    void registeredStub_isStillDispatchable() {
        CommandRegistry registry = CommandFactory.createDefault();
        CommandResult r = registry.dispatch("/review", CommandContext.minimal());
        assertEquals("/review: Not yet implemented", r.output());
    }

    @Test
    void commitCommands_areAntInternalStubs() {

        // These compatibility entries remain hidden, dispatchable stubs, and
        // the unsupported aliases must stay absent.
        CommandRegistry registry = CommandFactory.createDefault();
        assertEquals("/commit: Not yet implemented",
            registry.dispatch("/commit", CommandContext.minimal()).output());
        assertEquals("/commit-push-pr: Not yet implemented",
            registry.dispatch("/commit-push-pr", CommandContext.minimal()).output());
        assertTrue(registry.find("git-commit").isEmpty(),
            "self-invented alias git-commit must not be registered");
        assertTrue(registry.find("commit-pr").isEmpty(),
            "self-invented alias commit-pr must not be registered");
    }

    @Test
    void help_excludesHiddenStubs() {
        CommandRegistry registry = CommandFactory.createDefault();
        CommandResult r = registry.dispatch("/help", CommandContext.minimal());
        String out = r.output();
        // Sample of stubs registered in CommandFactory:
        assertFalse(Strings.CS.contains(out, "/review"), "/help must not surface hidden stub /review");
        assertFalse(Strings.CS.contains(out, "/ultraplan"), "/help must not surface hidden stub /ultraplan");
        assertFalse(Strings.CS.contains(out, "/terminal-setup"), "/help must not surface hidden stub /terminal-setup");
        // But real commands still appear:
        assertTrue(Strings.CS.contains(out, "/exit"), "/help must still surface real commands");
    }
}
