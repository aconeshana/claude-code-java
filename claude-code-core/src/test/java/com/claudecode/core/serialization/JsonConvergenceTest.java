package com.claudecode.core.serialization;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonConvergenceTest {
    @Test void safeJsonAndJsoncStripBomAndTolerateErrors() {
        assertEquals(1, JsonUtils.safeParseJson("\ufeff{\"a\":1}").path("a").asInt());
        assertNull(JsonUtils.safeParseJson("null"));
        assertNull(JsonUtils.safeParseJson("{"));
        assertEquals("http://x", JsonUtils.safeParseJsonc(
            "{ // c\n \"url\":\"http://x\", }").path("url").asText());
    }

    @Test void safeParseJsonRejectsTrailingTokensLikeJsonParse() {
        assertNull(JsonUtils.safeParseJson("{\"a\":1}garbage"));
        assertNull(JsonUtils.safeParseJson("{\"a\":1}{\"b\":2}"));
    }

    @Test void jsoncArrayAppendPreservesComments() {
        String result = JsonUtils.addItemToJsoncArray("[\n  // keep\n  {\"a\":1}\n]", Map.of("b", 2));
        assertTrue(Strings.CS.contains(result, "// keep"));
        assertEquals(2, JsonUtils.safeParseJsonc(result).size());
    }
}
