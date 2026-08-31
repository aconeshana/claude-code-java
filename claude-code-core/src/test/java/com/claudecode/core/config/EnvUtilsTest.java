package com.claudecode.core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class EnvUtilsTest {
    @Test
    void parsesCanonicalTruthyAndFalsySets() {
        assertTrue(EnvUtils.isEnvTruthy(" ON "));
        assertFalse(EnvUtils.isEnvTruthy("enabled"));
        assertTrue(EnvUtils.isEnvDefinedFalsy("off"));
        assertFalse(EnvUtils.isEnvDefinedFalsy(""));
    }

    @Test
    void parsesEnvValuesAtFirstEquals() {
        assertEquals("a=b", EnvUtils.parseEnvVars(List.of("KEY=a=b")).get("KEY"));
        assertThrows(IllegalArgumentException.class,
            () -> EnvUtils.parseEnvVars(List.of("INVALID")));
    }
}
