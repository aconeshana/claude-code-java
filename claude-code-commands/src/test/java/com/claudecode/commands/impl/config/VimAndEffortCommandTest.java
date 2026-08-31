package com.claudecode.commands.impl.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.testing.FakeSettingsManagementPort;
import com.claudecode.core.message.Usage;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VimAndEffortCommandTest {
    @Test void effortPersistsAndLiveAppliesThroughGroupedContext() {
        FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
        AtomicReference<String> live = new AtomicReference<>();
        CommandContext context = CommandContext.builder("m", List::of, () -> { }, _ -> { },
                () -> Usage.EMPTY, _ -> 0, ".", false)
            .settingsManagement(settings).effortValueSetter(live::set).build();
        String output = new EffortCommand().execute(context, "medium").output();
        assertTrue(Strings.CS.contains(output, "saved as your default"));
        assertEquals("medium", settings.effort);
        assertEquals("medium", live.get());
        assertEquals("Effort level set to auto",
            new EffortCommand().execute(context, "auto").output());
        assertNull(settings.effort);
    }
}
