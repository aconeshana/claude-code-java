package com.claudecode.core.memdir;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ports 's behavior 1:1 via explicit now/mtime pairs. */
class MemoryAgeTest {

    private static final long DAY_MS = 86_400_000L;

    @Test
    void ageDaysFloorRoundsAndClampsNegativeToZero() {
        long now = 10 * DAY_MS;
        assertEquals(0, MemoryAge.ageDays(now, now));
        assertEquals(0, MemoryAge.ageDays(now - DAY_MS / 2, now));
        assertEquals(1, MemoryAge.ageDays(now - DAY_MS, now));
        assertEquals(1, MemoryAge.ageDays(now - DAY_MS - 1, now));
        assertEquals(47, MemoryAge.ageDays(now - 47 * DAY_MS, now));
        // future mtime / clock skew clamps to 0, never negative
        assertEquals(0, MemoryAge.ageDays(now + DAY_MS, now));
    }

    @Test
    void ageHumanReadableStrings() {
        long now = 100 * DAY_MS;
        assertEquals("today", MemoryAge.age(now, now));
        assertEquals("yesterday", MemoryAge.age(now - DAY_MS, now));
        assertEquals("2 days ago", MemoryAge.age(now - 2 * DAY_MS, now));
        assertEquals("47 days ago", MemoryAge.age(now - 47 * DAY_MS, now));
    }

    @Test
    void freshnessTextEmptyForTodayAndYesterday() {
        long now = 100 * DAY_MS;
        assertEquals("", MemoryAge.freshnessText(now, now));
        assertEquals("", MemoryAge.freshnessText(now - DAY_MS, now));
    }

    @Test
    void freshnessTextWarnsBeyondOneDay() {
        long now = 100 * DAY_MS;
        String text = MemoryAge.freshnessText(now - 47 * DAY_MS, now);
        assertTrue(Strings.CS.contains(text, "47 days old"));
        assertTrue(Strings.CS.contains(text, "Verify against current code before asserting as fact."));
    }

    @Test
    void freshnessNoteEmptyForFreshMemory() {
        long now = 100 * DAY_MS;
        assertEquals("", MemoryAge.freshnessNote(now, now));
    }

    @Test
    void freshnessNoteWrapsInSystemReminderTag() {
        long now = 100 * DAY_MS;
        String note = MemoryAge.freshnessNote(now - 5 * DAY_MS, now);
        assertTrue(Strings.CS.startsWith(note, "<system-reminder>"));
        assertTrue(Strings.CS.contains(note, "5 days old"));
        assertTrue(Strings.CS.endsWith(note, "</system-reminder>\n"));
    }
}
