package com.claudecode.core.text;

import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnicodeSanitizerTest {

    @Test
    void normalizesNfkcAndRemovesFormatPrivateUseAndUnassignedCodePoints() {
        String input = "Ｆｅａｔ​ure\uE000\u0378";

        assertEquals("Feature", UnicodeSanitizer.sanitize(input));
    }

    @Test
    void preservesOrdinaryUnicodeAndWhitespace() {
        assertEquals("  功能 修复  ", UnicodeSanitizer.sanitize("  功能 修复  "));
    }

    @Test
    void recursivelySanitizesJsonKeysAndValues() throws Exception {
        var input = JsonUtils.getMapper().readTree("""
            {"na​me":"tool","nested":[{"de‮scription":"Ｆｏｏ"}],"count":2}
            """);

        assertEquals(
            JsonUtils.getMapper().readTree("""
                {"name":"tool","nested":[{"description":"Foo"}],"count":2}
                """),
            UnicodeSanitizer.sanitize(input));
    }
}
