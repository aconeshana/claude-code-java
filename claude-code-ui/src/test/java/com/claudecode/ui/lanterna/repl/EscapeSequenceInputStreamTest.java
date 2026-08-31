package com.claudecode.ui.lanterna.repl;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.DefaultKeyDecodingProfile;
import com.googlecode.lanterna.input.InputDecoder;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EscapeSequenceInputStreamTest {

    @Test
    void productionLoneEscapeWindowIsShorterThanSequenceContinuationWindow() {
        assertTrue(EscapeSequenceInputStream.DEFAULT_LONE_ESCAPE_TIMEOUT
            .compareTo(EscapeSequenceInputStream.DEFAULT_ESCAPE_TIMEOUT) < 0);
    }

    @Test
    void productionLoneEscapeWindowRetainsRemoteTerminalCompatibility() {
        assertEquals(EscapeSequenceInputStream.DEFAULT_LONE_ESCAPE_TIMEOUT,
            EscapeSequenceInputStream.productionLoneEscapeTimeout(Map.of()));

        for (String marker : List.of(
                "SSH_CONNECTION", "SSH_CLIENT", "SSH_TTY", "MOSH_IP", "TMUX", "STY")) {
            assertEquals(EscapeSequenceInputStream.DEFAULT_REMOTE_LONE_ESCAPE_TIMEOUT,
                EscapeSequenceInputStream.productionLoneEscapeTimeout(Map.of(marker, "present")),
                marker);
        }
    }

    @Test
    void localProductionWindowStillPreservesAQuicklySplitArrowSequence() throws Exception {
        try (Fixture fixture = new Fixture(
                EscapeSequenceInputStream.DEFAULT_ESCAPE_TIMEOUT,
                EscapeSequenceInputStream.DEFAULT_LONE_ESCAPE_TIMEOUT)) {
            fixture.write("\033");
            fixture.write("[A");

            assertEquals(KeyType.ARROW_UP,
                fixture.awaitKey(Duration.ofMillis(250)).getKeyType());
        }
    }

    @Test
    void terminalReaderHasDedicatedInteractivePriority() throws Exception {
        try (Fixture fixture = new Fixture(Duration.ofMillis(30))) {
            assertEquals(EscapeSequenceInputStream.INPUT_READER_PRIORITY,
                fixture.input.readerThreadPriority());
        }
    }

    @Test
    void splitSgrMouseSequenceNeverLeaksAsAnEscapeKey() throws Exception {
        try (Fixture fixture = new Fixture(Duration.ofMillis(50))) {
            fixture.write("\033");

            assertNull(fixture.decoder.getNextCharacter(false));

            fixture.write("[<64;112;22M");

            MouseAction action = assertInstanceOf(
                MouseAction.class, fixture.awaitKey(Duration.ofMillis(250)));
            assertEquals(MouseActionType.SCROLL_UP, action.getActionType());
            assertEquals(new TerminalPosition(111, 21), action.getPosition());
        }
    }

    @Test
    void completeContinuationWinsEvenWhenTheUiDoesNotPollUntilAfterTheDeadline() throws Exception {
        try (Fixture fixture = new Fixture(Duration.ofMillis(30))) {
            fixture.write("\033");
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
            fixture.write("[<65;112;22M");

            LockSupport.parkNanos(Duration.ofMillis(70).toNanos());

            MouseAction action = assertInstanceOf(
                MouseAction.class, fixture.decoder.getNextCharacter(false));
            assertEquals(MouseActionType.SCROLL_DOWN, action.getActionType());
        }
    }

    @Test
    void loneEscapeExpiresWithoutBlockingThePollingThread() throws Exception {
        try (Fixture fixture = new Fixture(Duration.ofMillis(30))) {
            fixture.write("\033");

            long pollStarted = System.nanoTime();
            assertNull(fixture.decoder.getNextCharacter(false));
            long pollElapsed = System.nanoTime() - pollStarted;

            assertTrue(pollElapsed < Duration.ofMillis(25).toNanos(),
                () -> "non-blocking poll took " + Duration.ofNanos(pollElapsed));
            assertEquals(KeyType.ESCAPE,
                fixture.awaitKey(Duration.ofMillis(250)).getKeyType());
        }
    }

    @Test
    void slashBackspaceAndEnterBypassTheEscapeTimeout() throws Exception {
        try (Fixture fixture = new Fixture(Duration.ofSeconds(1))) {
            long started = System.nanoTime();
            fixture.write("/\177\r");

            assertEquals('/', fixture.awaitKey(Duration.ofMillis(250)).getCharacter());
            assertEquals(KeyType.BACKSPACE,
                fixture.awaitKey(Duration.ofMillis(250)).getKeyType());
            assertEquals(KeyType.ENTER,
                fixture.awaitKey(Duration.ofMillis(250)).getKeyType());

            long elapsed = System.nanoTime() - started;
            assertTrue(elapsed < Duration.ofMillis(250).toNanos(),
                () -> "ordinary input waited for the one-second escape timeout: "
                    + Duration.ofNanos(elapsed));
        }
    }

    @Test
    void completeArrowSequenceIsReleasedWithoutWaitingForTimeout() throws Exception {
        try (Fixture fixture = new Fixture(Duration.ofSeconds(1))) {
            fixture.write("\033");
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
            fixture.write("[A");

            assertEquals(KeyType.ARROW_UP,
                fixture.awaitKey(Duration.ofMillis(250)).getKeyType());
        }
    }

    @Test
    void largeOrdinaryInputPreservesEveryByteAcrossQueueExpansion() throws Exception {
        try (Fixture fixture = new Fixture(Duration.ofSeconds(1))) {
            byte[] payload = new byte[4096];
            Arrays.fill(payload, (byte) 'x');

            fixture.write(payload);

            assertArrayEquals(payload, fixture.input.readNBytes(payload.length));
        }
    }

    @Test
    void splitOscResponseIsPublishedOnlyAfterItsTerminator() throws Exception {
        try (Fixture fixture = new Fixture(Duration.ofSeconds(1))) {
            byte[] prefix = "\033]0;terminal title".getBytes(StandardCharsets.UTF_8);
            fixture.write(prefix);
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());

            assertEquals(0, fixture.input.available());

            fixture.write(new byte[]{0x07});
            byte[] expected = Arrays.copyOf(prefix, prefix.length + 1);
            expected[expected.length - 1] = 0x07;
            assertArrayEquals(expected, fixture.input.readNBytes(expected.length));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final PipedInputStream source = new PipedInputStream(4096);
        private final PipedOutputStream writer;
        private final EscapeSequenceInputStream input;
        private final InputDecoder decoder;

        private Fixture(Duration timeout) throws IOException {
            writer = new PipedOutputStream(source);
            input = new EscapeSequenceInputStream(source, timeout);
            decoder = new InputDecoder(new InputStreamReader(input, StandardCharsets.UTF_8));
            decoder.addProfile(new DefaultKeyDecodingProfile());
        }

        private Fixture(Duration timeout, Duration loneEscapeTimeout) throws IOException {
            writer = new PipedOutputStream(source);
            input = new EscapeSequenceInputStream(source, timeout, loneEscapeTimeout);
            decoder = new InputDecoder(new InputStreamReader(input, StandardCharsets.UTF_8));
            decoder.addProfile(new DefaultKeyDecodingProfile());
        }

        private void write(String value) throws IOException {
            write(value.getBytes(StandardCharsets.UTF_8));
        }

        private void write(byte[] value) throws IOException {
            writer.write(value);
            writer.flush();
        }

        private KeyStroke awaitKey(Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            KeyStroke key;
            while ((key = decoder.getNextCharacter(false)) == null
                    && System.nanoTime() < deadline) {
                LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
            }
            assertTrue(key != null, () -> "no key decoded within " + timeout);
            return key;
        }

        @Override
        public void close() throws Exception {
            input.close();
            writer.close();
            source.close();
        }
    }
}
