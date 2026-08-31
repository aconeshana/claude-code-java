package com.claudecode.tools.monitor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MonitorFeatureGateTest {

    @Test
    void readsAmberSentinelFromOfficialGrowthBookCache() {
        var global = JsonUtils.getMapper().createObjectNode();
        global.putObject("cachedGrowthBookFeatures").put("tengu_amber_sentinel", true);

        assertTrue(MonitorFeatureGate.evaluate(Map.of(), global));
    }

    @Test
    void privacyModesDisableTheCachedGate() {
        var global = JsonUtils.getMapper().createObjectNode();
        global.putObject("cachedGrowthBookFeatures").put("tengu_amber_sentinel", true);

        assertFalse(MonitorFeatureGate.evaluate(Map.of("DISABLE_TELEMETRY", "0"), global));
        assertFalse(MonitorFeatureGate.evaluate(Map.of("CLAUDE_CODE_USE_BEDROCK", "1"), global));
    }

    @Test
    void scopedOverridesNestAndClearAfterFailure() {
        boolean baseline = MonitorFeatureGate.systemEnabled();

        MonitorFeatureGate.withSystemEnabled(false, () -> {
            assertFalse(MonitorFeatureGate.systemEnabled());
            MonitorFeatureGate.withSystemEnabled(true, () -> {
                assertTrue(MonitorFeatureGate.systemEnabled());
                return null;
            });
            assertFalse(MonitorFeatureGate.systemEnabled());
            return null;
        });

        assertThrows(IllegalStateException.class, () ->
            MonitorFeatureGate.withSystemEnabled(!baseline, () -> {
                throw new IllegalStateException("boom");
            }));
        assertEquals(baseline, MonitorFeatureGate.systemEnabled());
    }
}
