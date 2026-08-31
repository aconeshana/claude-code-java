package com.claudecode.ui.lanterna.dialog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TuiSudoPasswordPresenterTest {

    @Test
    void localPresenterReturnsOneShotPasswordToCoordinator() throws Exception {
        InteractionCoordinator coordinator = new InteractionCoordinator(() -> "session-1");
        coordinator.register(InteractionFeatures.SUDO_PASSWORD,
            new TuiSudoPasswordPresenter(coordinator, request -> {
                assertEquals("/usr/bin/sudo", request.executable());
                return SudoPasswordInteraction.Result.provided("secret".toCharArray());
            }));

        try (SudoPasswordInteraction.Result.Provided provided =
                (SudoPasswordInteraction.Result.Provided) coordinator.request(
                    new SudoPasswordInteraction.Request("/usr/bin/sudo", "sudo -v"))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            provided.writeTo(output);
            assertArrayEquals("secret\n".getBytes(StandardCharsets.UTF_8),
                output.toByteArray());
        }
    }
}
