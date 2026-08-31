package com.claudecode.commands.impl.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.ConfigLiveSetters;
import com.claudecode.commands.testing.FakeSettingsManagementPort;
import com.claudecode.core.message.Usage;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConfigCommandTest {
    @Test void readsAndWritesThroughSettingsPort() {
        FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
        settings.values.put("theme", "dark");
        CommandContext context = context(settings, null);
        assertEquals("theme = dark", new ConfigCommand().execute(context, "get theme").output());
        assertEquals("theme = light",
            new ConfigCommand().execute(context, "set theme light").output());
        assertEquals("light", settings.values.get("theme"));
    }

    @Test void validatesAndLiveAppliesUsingSameApplyPathAsUi() {
        FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
        AtomicReference<String> theme = new AtomicReference<>();
        CommandContext context = context(settings,
            new ConfigLiveSetters(null, theme::set, null, null, null));
        assertTrue(Strings.CS.contains(new ConfigCommand()
            .applySetting(context, "theme", "invalid").output(), "Invalid value"));
        new ConfigCommand().applySetting(context, "theme", "dark-ansi");
        assertEquals("dark-ansi", theme.get());
        assertEquals("dark-ansi", settings.values.get("theme"));
    }

    @Test void interactiveEntryOnlyLaunchesPresentationPort() {
        FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
        boolean[] opened = {false};
        CommandContext context = CommandContext.builder("m", List::of, () -> { }, _ -> { },
                () -> Usage.EMPTY, _ -> 0, ".", false)
            .settingsManagement(settings).configDialogLauncher(() -> opened[0] = true).build();
        assertTrue(new ConfigCommand().execute(context, "set theme light").silent());
        assertTrue(opened[0]);
    }

    @Test void subagentMaxDepthAcceptsOnlyReleasedRange() {
        FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
        CommandContext context = context(settings, null);

        assertEquals("subagentMaxDepth = 5", new ConfigCommand()
            .applySetting(context, "subagentMaxDepth", "5").output());
        assertEquals("5", settings.values.get("subagentMaxDepth"));
        assertTrue(Strings.CS.contains(new ConfigCommand()
            .applySetting(context, "subagentMaxDepth", "0").output(), "Invalid value"));
        assertTrue(Strings.CS.contains(new ConfigCommand()
            .applySetting(context, "subagentMaxDepth", "6").output(), "Invalid value"));
        assertTrue(Strings.CS.contains(new ConfigCommand()
            .applySetting(context, "subagentMaxDepth", "2.0").output(), "Invalid value"));
    }

    private static CommandContext context(FakeSettingsManagementPort settings,
                                          ConfigLiveSetters live) {
        return CommandContext.builder("m", List::of, () -> { }, _ -> { },
                () -> Usage.EMPTY, _ -> 0, ".", false)
            .settingsManagement(settings).configLiveSetters(live).build();
    }
}
