package com.claudecode.commands.impl.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.testing.FakeSettingsManagementPort;
import com.claudecode.core.message.Usage;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdvisorCommandTest {
    @Test void readsWritesAndClearsThroughPreferencesPort() {
        FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
        CommandContext context = CommandContext.builder("m", List::of, () -> { }, _ -> { },
                () -> Usage.EMPTY, _ -> 0, ".", false)
            .settingsManagement(settings).build();
        assertTrue(Strings.CS.contains(new AdvisorCommand().execute(context, "").output(),
            "No advisor model configured"));
        assertTrue(Strings.CS.contains(new AdvisorCommand().execute(context, "opus").output(),
            "Advisor set to opus"));
        assertEquals("opus", settings.advisor);
        assertTrue(Strings.CS.contains(new AdvisorCommand().execute(context, "").output(),
            "Current advisor model: opus"));
        assertTrue(Strings.CS.contains(
            new AdvisorCommand().execute(context, "off").output(), "cleared"));
        assertNull(settings.advisor);
    }
}
