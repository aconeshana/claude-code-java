package com.claudecode.core.util;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class WordSlugGeneratorTest {
    @Test void generatesReleasedLayouts() {
        assertTrue(WordSlugGenerator.generateWordSlug().matches("[^-]+-[^-]+-[^-]+"));
        assertTrue(WordSlugGenerator.generateShortWordSlug().matches("[^-]+-[^-]+"));
    }
}
