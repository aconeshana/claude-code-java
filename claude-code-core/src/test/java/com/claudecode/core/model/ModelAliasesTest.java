package com.claudecode.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ModelAliasesTest {

    @Test
    void recognizesAllAliases() {
        for (String a : new String[]{"sonnet", "opus", "haiku", "fable", "best",
                                      "sonnet[1m]", "opus[1m]", "fable[1m]", "opusplan",
                                      "sol", "luna"}) {
            assertTrue(ModelAliases.isModelAlias(a), a + " should be an alias");
        }
    }

    @Test
    void lowercasesAndTrims() {
        assertTrue(ModelAliases.isModelAlias("  OPUS  "));
        assertTrue(ModelAliases.isModelAlias("Sonnet"));
        assertTrue(ModelAliases.isModelAlias(" SOL "));
        assertTrue(ModelAliases.isModelAlias("Luna"));
    }

    @Test
    void rejectsNonAliases() {
        assertFalse(ModelAliases.isModelAlias("claude-opus-4-8"));
        assertFalse(ModelAliases.isModelAlias("gpt-4"));
        assertFalse(ModelAliases.isModelAlias(""));
        assertFalse(ModelAliases.isModelAlias(null));
    }
}
