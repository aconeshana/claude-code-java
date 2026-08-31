package com.claudecode.tools.web;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebFetchToolSchemaAndMarkdownTest {

    @Test
    void schema_hasUrlAndPromptAsRequired() {
        var schema = new WebFetchTool().inputSchema();
        var required = schema.get("required");
        assertTrue(Strings.CS.contains(required.toString(), "\"url\""),   "url must be required");
        assertTrue(Strings.CS.contains(required.toString(), "\"prompt\""), "prompt must be required");
        assertEquals(2, required.size(), "exactly 2 required fields to match TS");
    }

    @Test
    void schema_hasTwoProperties() {

        // dropped from the model-facing schema (see WebFetchTool.buildSchema).
        var props = new WebFetchTool().inputSchema().get("properties");
        assertTrue(props.has("url"));
        assertTrue(props.has("prompt"));
        assertFalse(props.has("timeout"));
    }

    @Test
    void htmlToMarkdown_convertsHeadings() {
        String md = WebFetchTool.htmlToMarkdown("<h1>Title</h1><h2>Sub</h2>");
        assertTrue(Strings.CS.contains(md, "# Title"), md);
        assertTrue(Strings.CS.contains(md, "## Sub"), md);
    }

    @Test
    void htmlToMarkdown_preservesLinks() {
        String md = WebFetchTool.htmlToMarkdown("<a href=\"https://example.com\">Click</a>");
        assertTrue(Strings.CS.contains(md, "[Click](https://example.com)"), md);
    }

    @Test
    void htmlToMarkdown_convertsBold() {
        String md = WebFetchTool.htmlToMarkdown("<strong>Bold</strong> and <b>also</b>");
        assertTrue(Strings.CS.contains(md, "**Bold**"), md);
        assertTrue(Strings.CS.contains(md, "**also**"), md);
    }

    @Test
    void htmlToMarkdown_stripsScriptAndStyle() {
        String md = WebFetchTool.htmlToMarkdown(
            "<html><head><style>.x{color:red}</style></head><body>"
            + "<script>alert(1)</script><p>Hello</p></body></html>");
        assertFalse(Strings.CS.contains(md, "color:red"), "style content must be stripped");
        assertFalse(Strings.CS.contains(md, "alert(1)"), "script content must be stripped");
        assertTrue(Strings.CS.contains(md, "Hello"));
    }

    @Test
    void htmlToMarkdown_decodesEntities() {
        String md = WebFetchTool.htmlToMarkdown("&lt;b&gt; &amp; &nbsp;");
        assertTrue(Strings.CS.contains(md, "<b>"), md);
        assertTrue(Strings.CS.contains(md, "&"), md);
    }

    @Test
    void htmlToText_isAliasForMarkdown() {
        // Backward compat — same output.
        String html = "<h1>Hello</h1><p>World</p>";
        assertEquals(WebFetchTool.htmlToMarkdown(html), WebFetchTool.htmlToText(html));
    }
}
