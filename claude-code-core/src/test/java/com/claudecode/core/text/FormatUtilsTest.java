package com.claudecode.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.text.FormatUtils.RelativeTimeNumeric;
import com.claudecode.core.text.FormatUtils.RelativeTimeStyle;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Test;

import java.util.Locale;

class FormatUtilsTest {

    // ── pre-existing methods (regression guards) ─────────────────────────────

    @Test
    void formatFileSizeMatchesTs() {
        assertEquals("500 bytes", FormatUtils.formatFileSize(500));
        assertEquals("1KB", FormatUtils.formatFileSize(1024));
        assertEquals("1.5KB", FormatUtils.formatFileSize(1536));
        assertEquals("1MB", FormatUtils.formatFileSize(1024 * 1024));
        assertEquals("1.5GB", FormatUtils.formatFileSize((long) (1.5 * 1024 * 1024 * 1024)));
    }

    @Test
    void formatFileSizeIsLocaleIndependentLikeJavascriptToFixed() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1.5KB", FormatUtils.formatFileSize(1536));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void formatTokensMatchesTs() {
        assertEquals("999", FormatUtils.formatTokens(999));
        assertEquals("1k", FormatUtils.formatTokens(1000));
        assertEquals("1.3k", FormatUtils.formatTokens(1300));
        assertEquals("1.5m", FormatUtils.formatTokens(1_500_000));
        assertEquals("2b", FormatUtils.formatTokens(2_000_000_000L));
        assertEquals("1t", FormatUtils.formatTokens(1_000_000_000_000L));
    }

    @Test
    void formatNumberUsesOfficialCompactOneDecimalDisplay() {
        assertEquals("900", FormatUtils.formatNumber(900));
        assertEquals("1.0k", FormatUtils.formatNumber(1_000));
        assertEquals("1.3k", FormatUtils.formatNumber(1_250));
        assertEquals("1.3k", FormatUtils.formatNumber(1_321));
        assertEquals("1.0m", FormatUtils.formatNumber(999_950));
        assertEquals("-1k", FormatUtils.formatNumber(-1_000));
        assertEquals("-1.3k", FormatUtils.formatNumber(-1_321));
        assertEquals("-1m", FormatUtils.formatNumber(-999_950));
        assertEquals("1.0t", FormatUtils.formatNumber(1_000_000_000_000L));
        assertEquals("-1t", FormatUtils.formatNumber(-1_000_000_000_000L));
    }

    @Test
    void shortSecondsAndCompactNumbersAreLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1.2s", FormatUtils.formatSecondsShort(1_234));
            assertEquals("1.5k", FormatUtils.formatTokens(1_500));
            assertEquals("1.5k", FormatUtils.formatNumber(1_500));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void formatCostMatchesTs() {
        assertEquals("$1.23", FormatUtils.formatCost(1.234));
        assertEquals("$0.0042", FormatUtils.formatCost(0.0042));
    }

    // ── truncate ─────────────────────────────────────────────────────────────

    @Test
    void truncateKeepsShortStrings() {
        assertEquals("abc", FormatUtils.truncate("abc", 5));
        assertEquals("", FormatUtils.truncate(null, 5));
    }

    @Test
    void truncateAppendsEllipsisCountedTowardMax() {
        // ellipsis counts toward max: max=4 → 3 chars + "…"
        assertEquals("abc…", FormatUtils.truncate("abcdef", 4));
        assertEquals("abcd", FormatUtils.truncate("abcd", 4));
        assertEquals("…", FormatUtils.truncate("abcdef", 1));
    }

    @Test
    void truncateSingleLineCutsAtNewline() {
        assertEquals("ab…", FormatUtils.truncateSingleLine("ab\ncd", 10));
        assertEquals("abc…", FormatUtils.truncateSingleLine("abcdef", 4));
    }

    @Test
    void truncateUtilitiesAreGraphemeAndTerminalWidthAware() {
        assertEquals("中…", FormatUtils.truncate("中文A", 3));
        assertEquals("…A", FormatUtils.truncateStartToWidth("中文A", 3));
        assertEquals("src/…/File.java", FormatUtils.truncatePathMiddle("src/deep/path/File.java", 15));
        assertEquals(List.of("中文", "ABC"), FormatUtils.wrapText("中文ABC", 4));
    }

    @Test
    void displayWidthMatchesBunStringWidthWithNarrowAmbiguousCharacters() {
        assertEquals(1, FormatUtils.displayWidth("a"));
        assertEquals(32, FormatUtils.displayWidth("abcdefghijklmnopqrstuvwxyz012345"));
        assertEquals(2, FormatUtils.displayWidth("会"));
        assertEquals(1, FormatUtils.displayWidth("…"));
        assertEquals(1, FormatUtils.displayWidth("·"));
        assertEquals(1, FormatUtils.displayWidth("e\u0301"));
        assertEquals(2, FormatUtils.displayWidth("😀"));
        assertEquals(2, FormatUtils.displayWidth("👨‍👩‍👧‍👦"));
        assertEquals(2, FormatUtils.displayWidth("🏳️‍🌈"));
        assertEquals(2, FormatUtils.displayWidth("🇨🇳"));
        assertEquals(2, FormatUtils.displayWidth("1️⃣"));
        assertEquals(1, FormatUtils.displayWidth("⚙"));
        assertEquals(2, FormatUtils.displayWidth("⚙️"));
        assertEquals(0, FormatUtils.displayWidth("\u200d"));
        assertEquals(0, FormatUtils.displayWidth("\u0007\t"));
    }

    // ── formatRelativeTimeAgo ────────────────────────────────────────────────

    @Test
    void relativeTimeNarrow() {
        Instant now = Instant.now();
        assertEquals("0s ago", FormatUtils.formatRelativeTimeAgo(now.minusSeconds(0), RelativeTimeStyle.NARROW));
        assertEquals("5m ago", FormatUtils.formatRelativeTimeAgo(now.minusSeconds(5 * 60), RelativeTimeStyle.NARROW));
        assertEquals("2h ago", FormatUtils.formatRelativeTimeAgo(now.minusSeconds(2 * 3600), RelativeTimeStyle.NARROW));
        assertEquals("3d ago", FormatUtils.formatRelativeTimeAgo(now.minusSeconds(3 * 86400), RelativeTimeStyle.NARROW));
    }

    @Test
    void relativeTimeShort() {
        Instant now = Instant.now();
        assertEquals("5 minutes ago", FormatUtils.formatRelativeTimeAgo(now.minusSeconds(5 * 60), RelativeTimeStyle.SHORT));
        assertEquals("1 day ago", FormatUtils.formatRelativeTimeAgo(now.minusSeconds(86400), RelativeTimeStyle.SHORT));
        assertEquals("—", FormatUtils.formatRelativeTimeAgo(null, RelativeTimeStyle.SHORT));
    }

    @Test
    void formatRelativeTimeSupportsReleasedStylesAndNumericAuto() {
        Instant now = Instant.parse("2026-07-30T12:00:00Z");
        assertEquals("yesterday", FormatUtils.formatRelativeTime(
            now.minusSeconds(86_400), RelativeTimeStyle.LONG, RelativeTimeNumeric.AUTO, now));
        assertEquals("tomorrow", FormatUtils.formatRelativeTime(
            now.plusSeconds(86_400), RelativeTimeStyle.SHORT, RelativeTimeNumeric.AUTO, now));
        assertEquals("1 hour ago", FormatUtils.formatRelativeTime(
            now.minusSeconds(3_600), RelativeTimeStyle.SHORT, RelativeTimeNumeric.AUTO, now));
        assertEquals("in 0 sec.", FormatUtils.formatRelativeTime(
            now.plusMillis(500), RelativeTimeStyle.SHORT, RelativeTimeNumeric.ALWAYS, now));
        assertEquals("now", FormatUtils.formatRelativeTime(
            now, RelativeTimeStyle.LONG, RelativeTimeNumeric.AUTO, now));
        assertEquals("5m ago", FormatUtils.formatRelativeTime(
            now.minusSeconds(300), RelativeTimeStyle.NARROW, RelativeTimeNumeric.AUTO, now));
        assertEquals("tomorrow", FormatUtils.formatRelativeTimeAgo(
            now.plusSeconds(86_400), RelativeTimeStyle.SHORT, RelativeTimeNumeric.AUTO, now));
        assertEquals("1 day ago", FormatUtils.formatRelativeTimeAgo(
            now.minusSeconds(86_400), RelativeTimeStyle.SHORT, RelativeTimeNumeric.AUTO, now));
    }

    @Test
    void relativeTimeFutureMatchesTsFormatRelativeTimeAgo() {
        Instant now = Instant.parse("2026-07-30T12:00:00Z");
        assertEquals("in 5m", FormatUtils.formatRelativeTimeAgo(now.plusSeconds(5 * 60), RelativeTimeStyle.NARROW, now));
        assertEquals("in 2 days", FormatUtils.formatRelativeTimeAgo(now.plusSeconds(2 * 86400), RelativeTimeStyle.SHORT, now));
        assertEquals("0s ago", FormatUtils.formatRelativeTimeAgo(now.plusMillis(500), RelativeTimeStyle.NARROW, now));
    }

    @Test
    void relativeTimeParenthesized() {
        Instant now = Instant.now();
        assertEquals("(just now)", FormatUtils.formatRelativeTimeAgo(now.minusSeconds(10), RelativeTimeStyle.PARENTHESIZED));
        assertEquals("(5m ago)", FormatUtils.formatRelativeTimeAgo(now.minusSeconds(5 * 60), RelativeTimeStyle.PARENTHESIZED));
        assertEquals("(3h ago)", FormatUtils.formatRelativeTimeAgo(now.minusSeconds(3 * 3600), RelativeTimeStyle.PARENTHESIZED));
        assertEquals("(2d ago)", FormatUtils.formatRelativeTimeAgo(now.minusSeconds(2 * 86400), RelativeTimeStyle.PARENTHESIZED));
        assertNull(FormatUtils.formatRelativeTimeAgo(null, RelativeTimeStyle.PARENTHESIZED));
    }

    // ── date / timestamp helpers ─────────────────────────────────────────────

    @Test
    void exportTimestampAndIso() {
        Instant t = Instant.parse("2026-07-20T14:30:15.123Z");
// export timestamp uses the system default zone.
        assertEquals(
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
                .withZone(ZoneId.systemDefault()).format(t),
            FormatUtils.formatExportTimestamp(t));
        assertTrue(FormatUtils.formatExportTimestamp(t).matches("\\d{4}-\\d{2}-\\d{2}-\\d{6}"));
        assertEquals("2026-07-20T14:30:15.123Z", FormatUtils.formatInstantIso(t));
        assertEquals("Jul 20", FormatUtils.formatMonthDay(t));
    }

// ── tab expansion  ─────────────────────────────────────

    @Test
    void expandTabsNoTabIsNoOp() {
        assertEquals("plain text", FormatUtils.expandTabs("plain text"));
        assertEquals("a\tb", FormatUtils.expandTabs("a\tb", 0)); // interval<=0 guard
        assertEquals("", FormatUtils.expandTabs(""));
        assertNull(FormatUtils.expandTabs(null));
    }

    @Test
    void expandTabsAlignsTo8ColumnInterval() {
        // tab at column 0 → 8 spaces
        assertEquals("        ", FormatUtils.expandTabs("\t"));
        // "abc\t" → 3 chars + 5 spaces = 8
        assertEquals("abc     ", FormatUtils.expandTabs("abc\t"));
        // "abcdefg\t" → 7 chars + 1 space = 8
        assertEquals("abcdefg ", FormatUtils.expandTabs("abcdefg\t"));
        // "abcdefgh\t" → 8 chars + 8 spaces (next interval boundary)
        assertEquals("abcdefgh        ", FormatUtils.expandTabs("abcdefgh\t"));
    }

    @Test
    void expandTabsCustomInterval() {
        assertEquals("a ", FormatUtils.expandTabs("a\t", 2));
        assertEquals("a  ", FormatUtils.expandTabs("a\t", 3));
    }

    @Test
    void expandTabsNewlineResetsColumn() {
        // second line's tab starts at column 0 again
        assertEquals("x       \ny       ", FormatUtils.expandTabs("x\t\ny\t"));
    }

    @Test
    void expandTabsSkipsAnsiSequences() {
        // an ANSI color before a tab must NOT consume column width
        String in = "\u001B[31m\t";
        assertEquals("\u001B[31m        ", FormatUtils.expandTabs(in));
        // tab between two colored runs
        String in2 = "\u001B[1ma\u001B[0m\tb";
        assertEquals("\u001B[1ma\u001B[0m       b", FormatUtils.expandTabs(in2));
    }

    @Test
    void expandTabsWideCharactersCountAsTwo() {
        // a CJK char (width 2) at column 0: "你\t" → 1 wide (col 0-1) + 6 spaces to reach col 8
        assertEquals("你      ", FormatUtils.expandTabs("你\t"));
        // two CJK chars (width 4): "你你\t" → +4 spaces to reach col 8
        assertEquals("你你    ", FormatUtils.expandTabs("你你\t"));
    }

    @Test
    void charDisplayWidthBasics() {
        assertEquals(1, FormatUtils.charDisplayWidth('a'));
        assertEquals(2, FormatUtils.charDisplayWidth('你'));
        assertEquals(0, FormatUtils.charDisplayWidth('\u001B'));
    }

    // ── flattenToSingleLine (2.1.236 `pm(us(x))`) ────────────────────────

    @Test
    void flattenToSingleLineDropsAnsiStyling() {
        assertEquals("bold link", FormatUtils.flattenToSingleLine(
            "\u001B[1mbold\u001B[0m \u001B]8;;http://x\u0007link\u001B]8;;\u0007"));
    }

    @Test
    void flattenToSingleLineDropsOscTerminatedByStringTerminator() {
        assertEquals("after", FormatUtils.flattenToSingleLine("\u001B]0;title\u001B\\after"));
    }

    @Test
    void flattenToSingleLineCollapsesEveryWhitespaceRun() {
        assertEquals("one two three", FormatUtils.flattenToSingleLine("  one\n\n\ttwo   three  "));
    }

    @Test
    void flattenToSingleLineDropsControlCharactersButKeepsTheGapTheyLeave() {
        // U+0007 is inside `pct`; the surrounding newline still separates the two words.
        assertEquals("a b", FormatUtils.flattenToSingleLine("a\u0007\nb"));
    }

    @Test
    void flattenToSingleLineCollapsesNonBreakingSpaceLikeEcmascript() {
        // Java's own \s would leave U+00A0 alone; ECMAScript's does not.
        assertEquals("a b", FormatUtils.flattenToSingleLine("a\u00A0\u00A0b"));
    }

    @Test
    void flattenToSingleLineKeepsPrintableUnicode() {
        assertEquals("caf\u00e9 \u4e2d\u6587 \ud83c\udf89",
            FormatUtils.flattenToSingleLine("caf\u00e9\n\u4e2d\u6587\t\ud83c\udf89"));
    }

    @Test
    void flattenToSingleLineTreatsBlankInputAsEmpty() {
        assertEquals("", FormatUtils.flattenToSingleLine(null));
        assertEquals("", FormatUtils.flattenToSingleLine("   \n\t "));
    }
}
