package com.claudecode.commands.impl.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.testing.FakeSettingsManagementPort;
import com.claudecode.core.message.Usage;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SandboxToggleCommandTest {
    @Test void noArgsLaunchesPresentationPort() {
        FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
        AtomicBoolean launched = new AtomicBoolean();
        CommandContext context = context(settings, () -> launched.set(true));
        assertTrue(new SandboxToggleCommand(settings.sandbox()).execute(context, "").silent());
        assertTrue(launched.get());
    }

    @Test void excludeUsesSandboxPortAndDeduplicates() {
        FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
        CommandContext context = context(settings, null);
        SandboxToggleCommand command = new SandboxToggleCommand(settings.sandbox());
        assertTrue(Strings.CS.contains(command.execute(context, "exclude \"npm test\"").output(),
            ".claude/settings.local.json"));
        command.execute(context, "exclude \"npm test\"");
        assertEquals(List.of("npm test"), settings.excludedCommands);
    }

    @Test void rejectsInventedSubcommands() {
        FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
        assertEquals("Error: Unknown subcommand \"on\". Available subcommand: exclude",
            new SandboxToggleCommand(settings.sandbox())
                .execute(context(settings, null), "on").output());
    }

    private static CommandContext context(FakeSettingsManagementPort settings,
                                          Runnable launcher) {
        return CommandContext.builder("m", List::of, () -> { }, _ -> { },
                () -> Usage.EMPTY, _ -> 0, ".", false)
            .settingsManagement(settings).sandboxDialogLauncher(launcher).build();
    }
}
