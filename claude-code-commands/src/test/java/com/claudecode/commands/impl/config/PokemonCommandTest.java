package com.claudecode.commands.impl.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.testing.FakeSettingsManagementPort;
import com.claudecode.core.message.Usage;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PokemonCommandTest {
    @Test void statusIsReadOnlyAndHatchPersistsOnlyAfterConfirmation() {
        FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
        AtomicReference<CommandContext.PokemonHatchRequest> confirmation = new AtomicReference<>();
        CommandContext context = CommandContext.builder("m", List::of, () -> { }, _ -> { },
                () -> Usage.EMPTY, _ -> 0, ".", false)
            .settingsManagement(settings).pokemonHatchLauncher(confirmation::set).build();
        assertTrue(Strings.CS.contains(
            new PokemonCommand().execute(context, "status").output(), "Pikachu"));
        assertNull(settings.pokemon);
        assertTrue(new PokemonCommand().execute(context, "hatch").silent());
        assertNull(settings.pokemon);
        confirmation.get().confirm().get();
        assertNotNull(settings.pokemon);
    }
}
