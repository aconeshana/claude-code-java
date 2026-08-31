package com.claudecode.services.config;

import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoDreamFeatureGateTest {

    @Test
    void absentRolloutDoesNotTurnOnDreamEvenWhenSettingIsExplicitlyTrue() {
        assertFalse(AutoDreamFeatureGate.evaluate(Map.of(), null, true));
        assertFalse(AutoDreamFeatureGate.evaluate(Map.of(),
            JsonUtils.getMapper().createObjectNode(), true));
    }

    @Test
    void releasedObjectRolloutHonorsExplicitSetting() throws Exception {
        var global = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{"tengu_onyx_plover":{"enabled":true}}}
            """);
        assertTrue(AutoDreamFeatureGate.evaluate(Map.of(), global, null));
        assertTrue(AutoDreamFeatureGate.evaluate(Map.of(), global, true));
        assertFalse(AutoDreamFeatureGate.evaluate(Map.of(), global, false));
    }

    @Test
    void availableAndBareBooleanRolloutsAreAccepted() throws Exception {
        var available = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{"tengu_onyx_plover":{"available":true}}}
            """);
        var bareBoolean = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{"tengu_onyx_plover":true}}
            """);
        assertTrue(AutoDreamFeatureGate.evaluate(Map.of(), available, null));
        assertTrue(AutoDreamFeatureGate.evaluate(Map.of(), bareBoolean, null));
    }

    @Test
    void privacyAndProviderModesDisableCachedRollout() throws Exception {
        var global = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{"tengu_onyx_plover":{"enabled":true}}}
            """);
        assertFalse(AutoDreamFeatureGate.evaluate(
            Map.of("DISABLE_TELEMETRY", "0"), global, true));
        assertFalse(AutoDreamFeatureGate.evaluate(
            Map.of("CLAUDE_CODE_USE_VERTEX", "1"), global, true));
    }

    @Test
    void scheduleUsesBinaryPositiveFiniteRolloutOverrides() throws Exception {
        var global = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{"tengu_onyx_plover":{
              "enabled":true,"minHours":1.5,"minSessions":2.5}}}
            """);
        AutoDreamFeatureGate.Schedule schedule = AutoDreamFeatureGate.schedule(global);
        assertEquals(1.5, schedule.minHours());
        assertEquals(2.5, schedule.minSessions());
    }

    @Test
    void scheduleFallsBackForAbsentOrMalformedOverrides() throws Exception {
        var global = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{"tengu_onyx_plover":{
              "enabled":true,"minHours":0,"minSessions":"five"}}}
            """);
        AutoDreamFeatureGate.Schedule schedule = AutoDreamFeatureGate.schedule(global);
        assertEquals(24.0, schedule.minHours());
        assertEquals(5.0, schedule.minSessions());
        AutoDreamFeatureGate.Schedule absent = AutoDreamFeatureGate.schedule(null);
        assertEquals(24.0, absent.minHours());
        assertEquals(5.0, absent.minSessions());
    }
}
