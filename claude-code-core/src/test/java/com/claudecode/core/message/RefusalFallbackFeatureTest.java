package com.claudecode.core.message;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefusalFallbackFeatureTest {

    private static boolean enabled(Map<String, String> env) {
        return RefusalFallbackFeature.enabled(env::get);
    }

    @Test
    void theGateFollowsJavaScriptTruthiness() {
        assertTrue(enabled(Map.of()));
        assertTrue(enabled(Map.of("CLAUDE_CODE_DISABLE_REFUSAL_FALLBACK", "")),
            "an empty string is falsy in TS, so the lane stays on");
        assertFalse(enabled(Map.of("CLAUDE_CODE_DISABLE_REFUSAL_FALLBACK", "1")));
        assertFalse(enabled(Map.of("CLAUDE_CODE_DISABLE_REFUSAL_FALLBACK", "false")),
            "any non-empty value is truthy — even the word false");
    }

    @Test
    void aMissingLookupIsTreatedAsAnEmptyEnvironment() {
        assertTrue(RefusalFallbackFeature.enabled(null));

        Map<String, String> nulls = new HashMap<>();
        nulls.put("CLAUDE_CODE_DISABLE_REFUSAL_FALLBACK", null);
        assertTrue(enabled(nulls));
    }

    @Test
    void theSettingToggleIsVisibleExactlyWhenTheLaneIsOn() {

        assertTrue(RefusalFallbackFeature.settingVisible(_ -> null));
        assertFalse(RefusalFallbackFeature.settingVisible(
            name -> Strings.CS.equals("CLAUDE_CODE_DISABLE_REFUSAL_FALLBACK", name) ? "1" : null));
    }
}
