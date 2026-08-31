package com.claudecode.ui.lanterna.features.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;


class PermissionsFeatureTest {

    @Test
    void addDirectoryCancelAndSuccessPreserveStandaloneCommandMessages() {
        PreferencesFeatureTest.CapturingSink sink = new PreferencesFeatureTest.CapturingSink();
        AtomicReference<String> applied = new AtomicReference<>();
        CommandContext context = PreferencesFeatureTest.contextBuilder()
            .addDirApply((_, path, remember) -> {
                applied.set(path + ":" + remember);
                return CommandResult.of("Added " + path);
            })
            .build();
        PermissionsFeature feature = new PermissionsFeature(context, sink);

        feature.handleAddDirResult(null, null);
        feature.handleAddDirResult("/tmp/work", null);
        feature.handleAddDirResult("/tmp/work", false);

        assertEquals("/tmp/work:false", applied.get());
        assertEquals(List.of("/add-dir"), sink.breadcrumbs);
        assertEquals(List.of(
            "  Did not add a working directory.",
            "  Did not add /tmp/work as a working directory.",
            "  ⎿  Added /tmp/work"), sink.lines);
    }
}
