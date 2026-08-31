package com.claudecode.core.paste;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;


class PastedRefParserTest {

    private record Content(int id, String type, String content) implements PastedRefParser.PastedContentLike {}

    @Test
    void formatPastedTextRef_zeroLinesOmitsSuffix() {
        assertEquals("[Pasted text #1]", PastedRefParser.formatPastedTextRef(1, 0));
    }

    @Test
    void formatPastedTextRef_withLinesIncludesSuffix() {
        assertEquals("[Pasted text #2 +1 lines]", PastedRefParser.formatPastedTextRef(2, 1));
    }

    @Test
    void formatImageRef() {
        assertEquals("[Image #3]", PastedRefParser.formatImageRef(3));
    }

    @Test
    void getPastedTextRefNumLines_countsNewlinesNotLines() {
        assertEquals(2, PastedRefParser.getPastedTextRefNumLines("a\nb\nc"));
        assertEquals(0, PastedRefParser.getPastedTextRefNumLines("single line"));
        assertEquals(0, PastedRefParser.getPastedTextRefNumLines(""));
    }

    @Test
    void getPastedTextRefNumLines_crlfCountsOnce() {
        assertEquals(1, PastedRefParser.getPastedTextRefNumLines("a\r\nb"));
    }

    @Test
    void parseReferences_findsTextAndImageRefs() {
        List<PastedRefParser.Ref> refs = PastedRefParser.parseReferences(
            "hello [Pasted text #1 +2 lines] world [Image #2]");

        assertEquals(2, refs.size());
        assertEquals(1, refs.getFirst().id());
        assertEquals(2, refs.get(1).id());
    }

    @Test
    void parseReferences_filtersOutIdZero() {
        List<PastedRefParser.Ref> refs = PastedRefParser.parseReferences("[Pasted text #0]");
        assertTrue(refs.isEmpty());
    }

    @Test
    void expandPastedTextRefs_replacesTextPlaceholderWithRealContent() {
        Map<Integer, Content> pasted = Map.of(2, new Content(2, "text", "line one\nline two"));

        String expanded = PastedRefParser.expandPastedTextRefs(
            "帮我搜索一下 [Pasted text #2 +1 lines] 里面的内容", pasted);

        assertEquals("帮我搜索一下 line one\nline two 里面的内容", expanded);
    }

    @Test
    void expandPastedTextRefs_leavesImageRefsUntouched() {
        Map<Integer, Content> pasted = Map.of(2, new Content(2, "image", "base64data=="));

        String expanded = PastedRefParser.expandPastedTextRefs("see [Image #2] attached", pasted);

        assertEquals("see [Image #2] attached", expanded,
            "image refs become content blocks elsewhere, never inlined as text");
    }

    @Test
    void expandPastedTextRefs_missingIdLeftAsIs() {
        String expanded = PastedRefParser.expandPastedTextRefs("orphan [Pasted text #99]", Map.of());
        assertEquals("orphan [Pasted text #99]", expanded);
    }

    @Test
    void expandPastedTextRefs_multipleRefsExpandInOriginalOrder() {
        Map<Integer, Content> pasted = Map.of(
            1, new Content(1, "text", "FIRST"),
            2, new Content(2, "text", "SECOND"));

        String expanded = PastedRefParser.expandPastedTextRefs(
            "[Pasted text #1] then [Pasted text #2]", pasted);

        assertEquals("FIRST then SECOND", expanded);
    }

    @Test
    void expandPastedTextRefs_noRefsReturnsInputUnchanged() {
        assertEquals("plain text, nothing pasted", PastedRefParser.expandPastedTextRefs("plain text, nothing pasted", Map.of()));
    }

    @Test
    void expandPastedTextRefs_nullOrEmptyInput() {
        assertNull(PastedRefParser.expandPastedTextRefs(null, Map.of()));
        assertEquals("", PastedRefParser.expandPastedTextRefs("", Map.of()));
    }
}
