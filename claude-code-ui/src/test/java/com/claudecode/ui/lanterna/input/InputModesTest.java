package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.claudecode.ui.lanterna.input.InputPanel.Mode;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the stateless {@link InputModes} prefix helpers.
 */
class InputModesTest {

    @Test
    void fromPrefix_detectsBashElseNormal() {
        assertEquals(Mode.BASH, InputModes.fromPrefix("!ls -la"));
        assertEquals(Mode.NORMAL, InputModes.fromPrefix("hello"));
        assertEquals(Mode.NORMAL, InputModes.fromPrefix(""));
        assertEquals(Mode.NORMAL, InputModes.fromPrefix(null), "null-safe → NORMAL");

        assertEquals(Mode.NORMAL, InputModes.fromPrefix("#foo"));
    }

    @Test
    void overrideFromPrefix_nullForNormal() {
        assertEquals(Mode.BASH, InputModes.overrideFromPrefix("!ls"));
        assertNull(InputModes.overrideFromPrefix("plain"), "NORMAL → null override");
        assertNull(InputModes.overrideFromPrefix("#foo"), "'#' is plain text → null override");
        assertNull(InputModes.overrideFromPrefix(null));
    }

    @Test
    void stripPrefix_removesOnlyLeadingBang() {
        assertEquals("ls -la", InputModes.stripPrefix("!ls -la"));
        assertEquals("hello", InputModes.stripPrefix("hello"));
        assertEquals("#foo", InputModes.stripPrefix("#foo"), "'#' is not stripped");
        assertEquals("", InputModes.stripPrefix(""));
        assertNull(InputModes.stripPrefix(null));
        // Only the FIRST char is stripped — an interior '!' is untouched.
        assertEquals("a!b", InputModes.stripPrefix("!a!b"));
    }

    @Test
    void prependPrefix_addsWhenMissing_idempotentWhenPresent() {
        assertEquals("!ls", InputModes.prependPrefix("ls", Mode.BASH));
        assertEquals("plain", InputModes.prependPrefix("plain", Mode.NORMAL));
        // Idempotent — already-prefixed text is left as-is.
        assertEquals("!ls", InputModes.prependPrefix("!ls", Mode.BASH));
    }

    @Test
    void stripThenPrepend_roundTrips() {
        for (String in : new String[] {"!ls -la", "plain text", "#foo is plain"}) {
            Mode m = InputModes.fromPrefix(in);
            assertEquals(in, InputModes.prependPrefix(InputModes.stripPrefix(in), m),
                "strip then prepend should reconstruct the original for " + in);
        }
    }
}
