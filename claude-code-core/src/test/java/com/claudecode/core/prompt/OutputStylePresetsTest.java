package com.claudecode.core.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class OutputStylePresetsTest {

    @Test
    void builtInStyleNamesUseTheOfficialCaseSensitiveKeys() {
        assertSame(OutputStylePresets.EXPLANATORY,
            OutputStylePresets.resolveByName("Explanatory"));
        assertSame(OutputStylePresets.LEARNING,
            OutputStylePresets.resolveByName("Learning"));
        assertNull(OutputStylePresets.resolveByName("explanatory"));
        assertNull(OutputStylePresets.resolveByName("learning"));
    }
}
