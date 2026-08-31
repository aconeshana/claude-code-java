package com.claudecode.tools.bash;

import com.claudecode.runtime.interaction.SudoPasswordInteraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SudoPasswordInteractionTest {

    @Test
    void providedPasswordCanBeWrittenOnlyOnceAndNeverAppearsInDiagnostics() throws Exception {
        char[] callerSecret = "local-password".toCharArray();
        SudoPasswordInteraction.Result.Provided provided =
            SudoPasswordInteraction.Result.provided(callerSecret);
        ByteArrayOutputStream destination = new ByteArrayOutputStream();

        provided.writeTo(destination);

        assertEquals("local-password\n", destination.toString(StandardCharsets.UTF_8));
        assertFalse(provided.toString().contains("local-password"));
        assertThrows(IllegalStateException.class,
            () -> provided.writeTo(new ByteArrayOutputStream()));
    }
}
