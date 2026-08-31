package com.claudecode.commands.policy;

import org.junit.jupiter.api.Test;

import static com.claudecode.commands.policy.ImmediateCommandStrategy.*;
import static org.junit.jupiter.api.Assertions.*;

class ImmediateCommandStrategyTest {

    @Test void antUserReturnsTrue() {
        assertTrue(inferenceConfigCommandImmediate(_ -> USER_TYPE_ANT));
    }

    @Test void nonAntUserReturnsFalse() {
        assertFalse(inferenceConfigCommandImmediate(_ -> "external"));
    }

    @Test void missingUserTypeReturnsFalse() {
        assertFalse(inferenceConfigCommandImmediate(_ -> null));
    }

    @Test void onlyUserTypeIsInspected() {
        int[] calls = {0};
        inferenceConfigCommandImmediate(name -> {
            calls[0]++;
            assertEquals(ENV_USER_TYPE, name);
            return "external";
        });
        assertEquals(1, calls[0]);
    }
}
