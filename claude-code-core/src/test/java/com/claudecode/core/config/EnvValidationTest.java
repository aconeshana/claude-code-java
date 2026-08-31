package com.claudecode.core.config;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EnvValidation#validateBoundedIntEnvVar} — the shared bounded-int
 * env validator backing both {@code /doctor}'s env section and the API-layer
 * {@code CLAUDE_CODE_MAX_OUTPUT_TOKENS} resolution.
 */
class EnvValidationTest {

    @Test
    void unsetIsValidWithDefault() {
        var r = EnvValidation.validateBoundedIntEnvVar("X", null, 100, 200);
        assertEquals("valid", r.status());
        assertEquals(100, r.effective());
        assertNull(r.message());

        assertEquals("valid", EnvValidation.validateBoundedIntEnvVar("X", "  ", 100, 200).status());
    }

    @Test
    void validWithinBound() {
        var r = EnvValidation.validateBoundedIntEnvVar("X", "150", 100, 200);
        assertEquals("valid", r.status());
        assertEquals(150, r.effective());
    }

    @Test
    void nonNumericIsInvalidWithDefault() {
        var r = EnvValidation.validateBoundedIntEnvVar("X", "abc", 100, 200);
        assertEquals("invalid", r.status());
        assertEquals(100, r.effective());
        assertNotNull(r.message());
    }

    @Test
    void zeroOrNegativeIsInvalid() {
        assertEquals("invalid", EnvValidation.validateBoundedIntEnvVar("X", "0", 100, 200).status());
        assertEquals("invalid", EnvValidation.validateBoundedIntEnvVar("X", "-5", 100, 200).status());
    }

    @Test
    void overUpperLimitIsCapped() {
        var r = EnvValidation.validateBoundedIntEnvVar("X", "500", 100, 200);
        assertEquals("capped", r.status());
        assertEquals(200, r.effective());
        assertTrue(Strings.CS.contains(r.message(), "200"));
    }

    @Test
    void leadingIntWithTrailingGarbageParsesLikeJsParseInt() {
        var r = EnvValidation.validateBoundedIntEnvVar("X", "150abc", 100, 200);
        assertEquals("valid", r.status());
        assertEquals(150, r.effective());
    }

    @Test
    void leadingWhitespaceIsSkipped() {
        var r = EnvValidation.validateBoundedIntEnvVar("X", "  150xyz", 100, 200);
        assertEquals("valid", r.status());
        assertEquals(150, r.effective());
    }
}
