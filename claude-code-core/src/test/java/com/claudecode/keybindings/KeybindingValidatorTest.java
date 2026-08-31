package com.claudecode.keybindings;

import org.apache.commons.lang3.Strings;
import com.claudecode.keybindings.KeybindingValidator.KeybindingWarning;
import com.claudecode.keybindings.KeybindingValidator.Severity;
import com.claudecode.keybindings.KeybindingValidator.WarningType;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link KeybindingValidator}.
 */
class KeybindingValidatorTest {

    // A config that is valid on every platform — uses only keys that are never
    // reserved (avoids ctrl+c/d/m, ctrl+z/\, and macOS cmd+*).
    private static final String VALID_CONFIG = """
        [
          {
            "context": "Global",
            "bindings": {
              "ctrl+r": "history:search",
              "ctrl+l": "app:redraw"
            }
          },
          {
            "context": "Chat",
            "bindings": {
              "escape": "chat:cancel",
              "enter": "chat:submit",
              "up": "history:previous"
            }
          }
        ]
        """;

    // ── happy path ──────────────────────────────────────────────────────────

    @Test
    void validConfig_hasNoWarnings() {
        List<KeybindingWarning> warnings = KeybindingValidator.validate(VALID_CONFIG);
        assertTrue(warnings.isEmpty(), "expected zero warnings, got: " + warnings);
    }

    // ── top-level structure ──────────────────────────────────────────────────

    @Test
    void topLevelNotArray_isParseError() {
        assertParseError("{}");
        assertParseError("123");
        assertParseError("\"bindings\"");
    }

    @Test
    void malformedJson_isParseError() {
        List<KeybindingWarning> warnings = KeybindingValidator.validate("{ not json");
        assertEquals(1, warnings.size());
        assertEquals(WarningType.PARSE_ERROR, warnings.getFirst().type());
        assertEquals(Severity.ERROR, warnings.getFirst().severity());
    }

    // ── context validity ─────────────────────────────────────────────────────

    @Test
    void unknownContext_isInvalidContext() {
        String json = """
            [
              {
                "context": "Foo",
                "bindings": { "ctrl+r": "history:search" }
              }
            ]
            """;
        KeybindingWarning w = assertSingle(json);
        assertEquals(WarningType.INVALID_CONTEXT, w.type());
        assertEquals(Severity.ERROR, w.severity());
        assertEquals("Foo", w.context());
    }

    @Test
    void missingContext_isParseError() {
        String json = """
            [
              { "bindings": { "ctrl+r": "history:search" } }
            ]
            """;
        KeybindingWarning w = assertSingle(json);
        assertEquals(WarningType.PARSE_ERROR, w.type());
        assertEquals(Severity.ERROR, w.severity());
    }

    // ── duplicate keys within one bindings object ────────────────────────────

    @Test
    void duplicateKeyInSameBindings_isDuplicateWarning() {
        String json = """
            [
              {
                "context": "Global",
                "bindings": {
                  "ctrl+r": "history:search",
                  "ctrl+r": "app:redraw"
                }
              }
            ]
            """;
        KeybindingWarning w = assertSingle(json);
        assertEquals(WarningType.DUPLICATE, w.type());
        assertEquals(Severity.WARNING, w.severity());
        assertEquals("ctrl+r", w.key());
        assertEquals("Global", w.context());
    }

    // ── reserved shortcuts ───────────────────────────────────────────────────

    @Test
    void reservedKey_ctrlC_isReservedError() {
        String json = """
            [
              {
                "context": "Global",
                "bindings": { "ctrl+c": "app:interrupt" }
              }
            ]
            """;
        KeybindingWarning w = assertSingle(json);
        assertEquals(WarningType.RESERVED, w.type());
        assertEquals(Severity.ERROR, w.severity());
        assertEquals("ctrl+c", w.key());
    }

    @Test
    void reservedTerminalKey_ctrlZ_isReservedWarning() {
        String json = """
            [
              {
                "context": "Global",
                "bindings": { "ctrl+z": "app:suspend" }
              }
            ]
            """;
        KeybindingWarning w = assertSingle(json);
        assertEquals(WarningType.RESERVED, w.type());
        assertEquals(Severity.WARNING, w.severity());
        assertEquals("ctrl+z", w.key());
    }

    // ── action validity ──────────────────────────────────────────────────────

    @Test
    void commandBinding_badFormat_isInvalidActionWarning() {
        String json = """
            [
              {
                "context": "Chat",
                "bindings": { "ctrl+r": "command:foo bar" }
              }
            ]
            """;
        KeybindingWarning w = assertSingle(json);
        assertEquals(WarningType.INVALID_ACTION, w.type());
        assertEquals(Severity.WARNING, w.severity());
    }

    @Test
    void commandBinding_inNonChatContext_isInvalidActionWarning() {
        String json = """
            [
              {
                "context": "Global",
                "bindings": { "ctrl+r": "command:foo" }
              }
            ]
            """;
        KeybindingWarning w = assertSingle(json);
        assertEquals(WarningType.INVALID_ACTION, w.type());
        assertEquals(Severity.WARNING, w.severity());
        assertEquals("Global", w.context());
    }

    @Test
    void actionNotString_isInvalidActionError() {
        String json = """
            [
              {
                "context": "Global",
                "bindings": { "ctrl+r": { "nested": true } }
              }
            ]
            """;
        KeybindingWarning w = assertSingle(json);
        assertEquals(WarningType.INVALID_ACTION, w.type());
        assertEquals(Severity.ERROR, w.severity());
    }

    @Test
    void voicePushToTalk_bareLetter_isInvalidActionWarning() {
        String json = """
            [
              {
                "context": "Chat",
                "bindings": { "k": "voice:pushToTalk" }
              }
            ]
            """;
        KeybindingWarning w = assertSingle(json);
        assertEquals(WarningType.INVALID_ACTION, w.type());
        assertEquals(Severity.WARNING, w.severity());
    }

    // ── keystroke syntax ─────────────────────────────────────────────────────

    @Test
    void emptyKeyPart_isParseError() {
        String json = """
            [
              {
                "context": "Global",
                "bindings": { "ctrl++r": "history:search" }
              }
            ]
            """;
        KeybindingWarning w = assertSingle(json);
        assertEquals(WarningType.PARSE_ERROR, w.type());
        assertEquals(Severity.ERROR, w.severity());
    }

    // ── formatting ───────────────────────────────────────────────────────────

    @Test
    void formatWarnings_empty_returnsEmptyString() {
        assertEquals("", KeybindingValidator.formatWarnings(List.of()));
    }

    @Test
    void formatWarnings_groupsBySeverity() {
        List<KeybindingWarning> warnings = List.of(
            new KeybindingWarning(WarningType.RESERVED, Severity.ERROR,
                "boom", "ctrl+c", "Global", null, "fix it"),
            new KeybindingWarning(WarningType.DUPLICATE, Severity.WARNING,
                "dup", "ctrl+r", "Global", null, null));
        String out = KeybindingValidator.formatWarnings(warnings);
        assertTrue(Strings.CS.contains(out, "1 keybinding error"));
        assertTrue(Strings.CS.contains(out, "1 keybinding warning"));
        assertTrue(Strings.CS.contains(out, "✗"));
        assertTrue(Strings.CS.contains(out, "⚠"));
        // errors printed before warnings
        assertTrue(out.indexOf("error") < out.indexOf("warning"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void assertParseError(String json) {
        List<KeybindingWarning> warnings = KeybindingValidator.validate(json);
        assertEquals(1, warnings.size());
        assertEquals(WarningType.PARSE_ERROR, warnings.getFirst().type());
        assertEquals(Severity.ERROR, warnings.getFirst().severity());
    }

    private static KeybindingWarning assertSingle(String json) {
        List<KeybindingWarning> warnings = KeybindingValidator.validate(json);
        assertEquals(1, warnings.size(), "expected exactly one warning, got: " + warnings);
        return warnings.getFirst();
    }
}
