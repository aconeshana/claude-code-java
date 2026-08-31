package com.claudecode.core.text;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;


class XmlTagUtilsTest {

    // ── extractTag ────────────────────────────────────────────────────────────


    @Test
    void extractTag_nullInput_returnsEmpty() {
        assertEquals(Optional.empty(), XmlTagUtils.extractTag(null, "foo"));
    }


    @Test
    void extractTag_blankInput_returnsEmpty() {
        assertEquals(Optional.empty(), XmlTagUtils.extractTag("   ", "foo"));
    }


    @Test
    void extractTag_nullTagName_returnsEmpty() {
        assertEquals(Optional.empty(), XmlTagUtils.extractTag("<foo>bar</foo>", null));
    }


    @Test
    void extractTag_blankTagName_returnsEmpty() {
        assertEquals(Optional.empty(), XmlTagUtils.extractTag("<foo>bar</foo>", "  "));
    }


    @Test
    void extractTag_missingTag_returnsEmpty() {
        assertEquals(Optional.empty(), XmlTagUtils.extractTag("hello world", "foo"));
    }


    @Test
    void extractTag_simpleTag_returnsContent() {
        Optional<String> result = XmlTagUtils.extractTag("<foo>hello</foo>", "foo");
        assertEquals(Optional.of("hello"), result);
    }


    @Test
    void extractTag_multilineContent_returnsFullContent() {
        String text = "<plan>\nline1\nline2\n</plan>";
        Optional<String> result = XmlTagUtils.extractTag(text, "plan");
        assertEquals(Optional.of("\nline1\nline2\n"), result);
    }


    @Test
    void extractTag_emptyTagContent_returnsEmpty() {
        assertEquals(Optional.empty(), XmlTagUtils.extractTag("<foo></foo>", "foo"));
    }


    @Test
    void extractTag_openingTagWithAttributes_returnsContent() {
        String text = "<tool_use_error code=\"42\" type=\"text\">something went wrong</tool_use_error>";
        Optional<String> result = XmlTagUtils.extractTag(text, "tool_use_error");
        assertEquals(Optional.of("something went wrong"), result);
    }


    @Test
    void extractTag_nestedSameNameTag_leftmostShortestMatch() {
        String text = "<wrap>outer<wrap>inner</wrap></wrap>";
        Optional<String> result = XmlTagUtils.extractTag(text, "wrap");
        assertEquals(Optional.of("outer<wrap>inner"), result);
    }


    @Test
    void extractTag_tagInsideDifferentOuterTag_returnsContent() {
        String text = "<section><item>value</item></section>";
        Optional<String> result = XmlTagUtils.extractTag(text, "item");
        // beforeMatch for the <item> match is "<section>" — zero <item> openings → depth=0
        assertEquals(Optional.of("value"), result);
    }


    @Test
    void extractTag_hyphenatedTagName_returnsContent() {
        String text = "<bash-input>ls -la</bash-input>";
        Optional<String> result = XmlTagUtils.extractTag(text, "bash-input");
        assertEquals(Optional.of("ls -la"), result);
    }


    @Test
    void extractTag_underscoredTagName_returnsContent() {
        String text = "<command_name>compact</command_name>";
        Optional<String> result = XmlTagUtils.extractTag(text, "command_name");
        assertEquals(Optional.of("compact"), result);
    }


    @Test
    void extractTag_caseInsensitiveMatch_returnsContent() {
        Optional<String> result = XmlTagUtils.extractTag("<FOO>bar</FOO>", "foo");
        assertEquals(Optional.of("bar"), result);
    }


    @Test
    void extractTag_multipleTopLevelTags_returnsFirst() {
        String text = "<item>first</item><item>second</item>";
        Optional<String> result = XmlTagUtils.extractTag(text, "item");
        assertEquals(Optional.of("first"), result);
    }

    // ── containsTag ──────────────────────────────────────────────────────────


    @Test
    void containsTag_presentTag_returnsTrue() {
        assertTrue(XmlTagUtils.containsTag("<err>oops</err>", "err"));
    }

    /** containsTag: absent tag returns false. */
    @Test
    void containsTag_missingTag_returnsFalse() {
        assertFalse(XmlTagUtils.containsTag("no tags here", "err"));
    }

    /** containsTag: null input returns false (no throw). */
    @Test
    void containsTag_nullInput_returnsFalse() {
        assertFalse(XmlTagUtils.containsTag(null, "foo"));
    }


    @Test
    void containsTag_emptyContentTag_returnsFalse() {
        assertFalse(XmlTagUtils.containsTag("<foo></foo>", "foo"));
    }

    // ── wrap ─────────────────────────────────────────────────────────────────


    @Test
    void wrap_normalContent_producesPairedTags() {
        assertEquals("<bash-input>ls -la</bash-input>", XmlTagUtils.wrap("bash-input", "ls -la"));
    }


    @Test
    void wrap_emptyContent_producesEmptyPair() {
        assertEquals("<tag></tag>", XmlTagUtils.wrap("tag", ""));
    }


    @Test
    void wrap_nullContent_treatedAsEmpty() {
        assertEquals("<tag></tag>", XmlTagUtils.wrap("tag", null));
    }


    @Test
    void wrap_specialCharsInContent_passthroughNotEscaped() {
        String content = "a < b && c > d & 'quoted' \"double\"";
        assertEquals("<msg>" + content + "</msg>", XmlTagUtils.wrap("msg", content));
    }


    @Test
    void wrap_hyphenatedAndUnderscoredTagName_reproduceTagVerbatim() {
        assertEquals("<command-args>foo</command-args>", XmlTagUtils.wrap("command-args", "foo"));
        assertEquals("<command_name>bar</command_name>", XmlTagUtils.wrap("command_name", "bar"));
    }

/** wrap: null tagName must throw IllegalArgumentException. */
    @Test
    void wrap_nullTagName_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> XmlTagUtils.wrap(null, "content"));
    }

    /** wrap(): blank tagName must throw IllegalArgumentException. */
    @Test
    void wrap_blankTagName_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> XmlTagUtils.wrap("  ", "content"));
    }

    // ── stripAll ─────────────────────────────────────────────────────────────

    /** stripAll: null input returns null (no throw). */
    @Test
    void stripAll_nullInput_returnsNull() {
        assertNull(XmlTagUtils.stripAll(null));
    }

    /** stripAll: empty input returns empty string. */
    @Test
    void stripAll_emptyInput_returnsEmpty() {
        assertEquals("", XmlTagUtils.stripAll(""));
    }

    /** stripAll: removes a paired XML tag and its inner content. */
    @Test
    void stripAll_singleTag_removesTagAndContent() {
        String result = XmlTagUtils.stripAll("before<think>secret</think>after");
        assertEquals("beforeafter", result);
    }

    /** stripAll: removes self-closing tags. */
    @Test
    void stripAll_selfClosingTag_removesTag() {
        String result = XmlTagUtils.stripAll("text <br/> more");
        assertEquals("text  more", result);
    }

    /** stripAll: plain text without tags is returned unchanged. */
    @Test
    void stripAll_noTags_returnsUnchanged() {
        assertEquals("plain text", XmlTagUtils.stripAll("plain text"));
    }
}
