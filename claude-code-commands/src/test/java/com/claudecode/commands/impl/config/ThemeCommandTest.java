package com.claudecode.commands.impl.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.ConfigLiveSetters;
import com.claudecode.commands.testing.FakeSettingsManagementPort;
import com.claudecode.core.message.Usage;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ThemeCommandTest {
    @Test void pickerReadsPortAndDialogApplyPersistsAndLiveApplies() {
        FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
        settings.theme = "light";
        AtomicReference<String> opened = new AtomicReference<>();
        AtomicReference<String> applied = new AtomicReference<>();
        CommandContext context = CommandContext.builder("m", List::of, () -> { }, _ -> { },
                () -> Usage.EMPTY, _ -> 0, ".", false)
            .settingsManagement(settings)
            .themeDialogLauncher(opened::set)
            .configLiveSetters(new ConfigLiveSetters(null, applied::set, null, null, null))
            .build();
        assertTrue(new ThemeCommand().execute(context, "dark").silent());
        assertEquals("light", opened.get());
        assertEquals("Theme set to dark-ansi",
            new ThemeCommand().applyFromDialog(context, "dark-ansi").output());
        assertEquals("dark-ansi", settings.theme);
        assertEquals("dark-ansi", applied.get());
    }
}
