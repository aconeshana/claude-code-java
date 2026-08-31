package com.claudecode.ui.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TmTokenizerLazyLoadingTest {

    @AfterEach
    void resetTokenizer() {
        TmTokenizer.resetForTests();
    }

    @Test
    void languageDiscoveryLoadsOnlyTheIndexAndGrammarsLoadOnDemand() {
        TmTokenizer.resetForTests();

        assertTrue(TmTokenizer.knownLanguages().contains("java"));
        assertEquals(0, TmTokenizer.loadedGrammarCountForTests(),
            "listing aliases must not parse every bundled grammar");

        assertNotNull(TmTokenizer.tokenize("class Example {}", "java"));
        int afterJava = TmTokenizer.loadedGrammarCountForTests();
        assertTrue(afterJava >= 1);
        assertTrue(afterJava < TmTokenizer.uniqueGrammarCountForTests(),
            "first highlight should load only the requested grammar dependency graph");

        assertNotNull(TmTokenizer.tokenize("def example():\n    return 1", "python"));
        assertTrue(TmTokenizer.loadedGrammarCountForTests() > afterJava,
            "a second unrelated language should load only when requested");
    }
}
