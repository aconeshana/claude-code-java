package com.claudecode.core.util;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SemverUtilsTest {
    @Test void comparesReleaseAndPrereleaseVersions() {
        assertTrue(SemverUtils.gt("v2.0.0", "1.9.9"));
        assertTrue(SemverUtils.lt("1.0.0-beta.2", "1.0.0"));
        assertEquals(0, SemverUtils.order("1.2.3+one", "1.2.3+two"));
    }

    @Test void supportsCommonRangeForms() {
        assertTrue(SemverUtils.satisfies("1.5.0", ">=1.2.0 <2.0.0"));
        assertTrue(SemverUtils.satisfies("1.4.9", "^1.2.3"));
        assertFalse(SemverUtils.satisfies("2.0.0", "^1.2.3"));
        assertTrue(SemverUtils.satisfies("1.2.8", "~1.2.3"));
        assertTrue(SemverUtils.satisfies("1.9.0", "1.x"));
        assertTrue(SemverUtils.satisfies("3.0.0", "1.x || >=3.0.0"));
    }
}
