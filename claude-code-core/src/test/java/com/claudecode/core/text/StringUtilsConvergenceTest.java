package com.claudecode.core.text;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StringUtilsConvergenceTest {
    @Test void coversRemainingOriginalStringUtilities() {
        assertEquals("a\\+b", StringUtils.escapeRegExp("a+b"));
        assertEquals("123", StringUtils.normalizeFullWidthDigits("１２３"));
        assertEquals("a b", StringUtils.normalizeFullWidthSpace("a　b"));
        assertEquals("a...[truncated]", StringUtils.safeJoinLines(List.of("a", "bbbb"), ",", 5));
        assertEquals("a\nb…", StringUtils.truncateToLines("a\nb\nc", 2));
    }

    @Test void accumulatorKeepsHeadAndReportsRemovedSize() {
        var acc = new StringUtils.EndTruncatingAccumulator(4);
        acc.append("abc");
        acc.append("def");
        assertTrue(acc.truncated());
        assertEquals(6, acc.totalBytes());
        assertTrue(Strings.CS.startsWith(acc.toString(), "abcd\n... [output truncated"));
    }
}
