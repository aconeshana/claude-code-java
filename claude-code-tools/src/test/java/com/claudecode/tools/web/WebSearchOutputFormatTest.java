package com.claudecode.tools.web;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.message.WebSearchToolResultBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WebSearchOutputFormatTest {

    private static final ObjectMapper M = new ObjectMapper();

    private WebSearchTool tool() {
        return new WebSearchTool(null); // no search provider → stub
    }

    /** Build a fake Brave search response. */
    private static ObjectNode fakeBraveResponse() {
        ObjectNode root = M.createObjectNode();
        ObjectNode web = root.putObject("web");
        ArrayNode results = web.putArray("results");
        ObjectNode r = results.addObject();
        r.put("title", "Brave Result");
        r.put("url", "https://brave.example.com");
        r.put("description", "A snippet from brave.");
        return root;
    }

    @Test
    void anthropicFormat_containsJsonLinks() throws Exception {


        var m = WebSearchTool.class.getDeclaredMethod(
            "formatAnthropicSearchResults", String.class, Map.class, Map.class);
        m.setAccessible(true);
        Map<Integer, WebSearchToolResultBlock> results = new TreeMap<>();
        results.put(0, new WebSearchToolResultBlock(
            "srvtoolu_1",
            List.of(new WebSearchToolResultBlock.Hit("Example Title", "https://example.com/page")),
            null));
        Map<Integer, StringBuilder> textBlocks = Map.of();
        String out = (String) m.invoke(tool(), "test query", textBlocks, results);
        assertTrue(Strings.CS.contains(out, "Links:"),
            "Anthropic output must include a Links: JSON block; got: " + out);
        assertTrue(Strings.CS.contains(out, "\"title\":\"Example Title\""),
            "output must include the hit title; got: " + out);
        assertTrue(Strings.CS.contains(out, "\"url\":\"https://example.com/page\""),
            "output must include the hit url; got: " + out);
    }

    @Test
    void braveFormat_containsMarkdownLink() throws Exception {
        var m = WebSearchTool.class.getDeclaredMethod(
            "formatBraveResults",
            JsonNode.class, List.class);
        m.setAccessible(true);
        String out = (String) m.invoke(tool(), fakeBraveResponse(), List.of());
        assertTrue(Strings.CS.contains(out, "[Brave Result](https://brave.example.com)"),
            "Brave output must use markdown links; got: " + out);
        assertTrue(Strings.CS.contains(out, "> A snippet from brave."), "must include snippet as blockquote");
    }

    @Test
    void bothFormats_endWithSourcesReminder() throws Exception {
        var m1 = WebSearchTool.class.getDeclaredMethod(
            "formatAnthropicSearchResults", String.class, Map.class, Map.class);
        m1.setAccessible(true);
        Map<Integer, WebSearchToolResultBlock> results = new TreeMap<>();
        results.put(0, new WebSearchToolResultBlock(
            "srvtoolu_1",
            List.of(new WebSearchToolResultBlock.Hit("Example Title", "https://example.com/page")),
            null));
        Map<Integer, StringBuilder> textBlocks = Map.of();
        String anthropicOut = (String) m1.invoke(tool(), "test query", textBlocks, results);

        var m2 = WebSearchTool.class.getDeclaredMethod(
            "formatBraveResults",
            JsonNode.class, List.class);
        m2.setAccessible(true);
        String braveOut = (String) m2.invoke(tool(), fakeBraveResponse(), List.of());

        for (String out : List.of(anthropicOut, braveOut)) {

            assertTrue(Strings.CS.contains(out, "REMINDER: You MUST include the sources above"),
                "output must end with the TS REMINDER; got: " + out.substring(Math.max(0, out.length() - 100)));
            assertTrue(Strings.CS.contains(out, "markdown hyperlinks"), "must mention markdown hyperlinks");
        }
    }

    @Test
    void schema_noLongerHasDenyDomains() {
        var props = new WebSearchTool(null).inputSchema().get("properties");
        assertFalse(props.has("deny_domains"),
            "deny_domains must be removed — use blocked_domains (TS canonical)");
    }
}
