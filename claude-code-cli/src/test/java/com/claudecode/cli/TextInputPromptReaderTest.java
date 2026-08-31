package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TextInputPromptReaderTest {

    @Test
    void ttyLeavesTheArgvPromptUntouched() throws Exception {
        TextInputPromptReader.Result result = TextInputPromptReader.resolve(
            "ARGV", new ByteArrayInputStream("IGNORED".getBytes(StandardCharsets.UTF_8)),
            true, Duration.ofMillis(20));

        assertEquals("ARGV", result.prompt());
        assertFalse(result.timedOut());
    }

    @Test
    void eofWithoutBytesDoesNotCountAsTheThreeSecondTimeout() throws Exception {
        TextInputPromptReader.Result result = TextInputPromptReader.resolve(
            "ARGV", InputStream.nullInputStream(), false, Duration.ofMillis(20));

        assertEquals("ARGV", result.prompt());
        assertFalse(result.timedOut());
    }

    @Test
    void inheritedOpenPipeTimesOutWhenNoByteEverArrives() throws Exception {
        try (PipedInputStream idlePipe = new PipedInputStream();
             PipedOutputStream producer = new PipedOutputStream(idlePipe)) {
            TextInputPromptReader.Result result = TextInputPromptReader.resolve(
                "ARGV", idlePipe, false, Duration.ofMillis(20));

            assertEquals("ARGV", result.prompt());
            assertTrue(result.timedOut());
        }
    }
}
