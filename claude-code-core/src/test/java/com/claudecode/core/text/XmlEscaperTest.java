package com.claudecode.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XmlEscaperTest {

    @Test
    void escapeText_matchesOriginalXmlUtility() {
        assertEquals("&amp;&lt;tag&gt;'\"", XmlEscaper.escapeText("&<tag>'\""));
        assertEquals("", XmlEscaper.escapeText(null));
    }

    @Test
    void escapeAttribute_escapesBothQuoteKinds() {
        assertEquals("&amp;&lt;tag&gt;&apos;&quot;", XmlEscaper.escapeAttribute("&<tag>'\""));
    }
}
