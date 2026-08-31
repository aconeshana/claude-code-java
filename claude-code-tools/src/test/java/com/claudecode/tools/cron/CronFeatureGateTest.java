package com.claudecode.tools.cron;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CronFeatureGateTest {

    @Test
    void cronEnablementUsesOnlyTheLocalDisableOverride() throws Exception {
        var root = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{
              "tengu_kairos_cron":false,
              "tengu_kairos_cron_durable":false
            }}
            """);

        CronFeatureGate gate = CronFeatureGate.evaluate(Map.of(), root);

        assertTrue(gate.cronEnabled());
        assertFalse(gate.durableEnabled());
    }

    @Test
    void defaultsRemainEnabledWhenGrowthBookIsUnavailable() throws Exception {
        var root = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{
              "tengu_kairos_cron":false,
              "tengu_kairos_cron_durable":false
            }}
            """);

        CronFeatureGate gate = CronFeatureGate.evaluate(
            Map.of("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"), root);

        assertTrue(gate.cronEnabled());
        assertTrue(gate.durableEnabled());
    }

    @Test
    void localCronDisableOverrideWins() {
        CronFeatureGate gate = CronFeatureGate.evaluate(
            Map.of("CLAUDE_CODE_DISABLE_CRON", "true"), null);

        assertFalse(gate.cronEnabled());
        assertTrue(gate.durableEnabled());
    }

    @Test
    void officialLocalJitterDefaultsAreAvailableWithoutGrowthBook() {
        CronJitterConfig config = CronFeatureGate.evaluate(Map.of(), null).jitterConfig();

        assertEquals(0.5d, config.recurringFrac());
        assertEquals(30 * 60 * 1_000L, config.recurringCapMs());
        assertEquals(7L * 24 * 60 * 60 * 1_000L, config.recurringMaxAgeMs());
        assertEquals(15_000L, config.cacheLeadMs());
    }

    @Test
    void validatesGrowthBookJitterAsAnAllOrNothingConfig() throws Exception {
        var valid = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{
              "tengu_kairos_cron_config":{
                "recurringFrac":0.25,"recurringCapMs":1234,
                "oneShotMaxMs":5000,"oneShotFloorMs":1000,
                "oneShotMinuteMod":15,"recurringMaxAgeMs":6000,
                "cacheLeadMs":7000
              }
            }}
            """);
        CronJitterConfig config = CronFeatureGate.evaluate(Map.of(), valid).jitterConfig();
        assertEquals(new CronJitterConfig(0.25, 1234, 5000, 1000, 15, 6000, 7000), config);

        var invalid = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{"tengu_kairos_cron_config":{
              "recurringFrac":2,"recurringCapMs":1234,
              "oneShotMaxMs":5000,"oneShotFloorMs":1000,
              "oneShotMinuteMod":15
            }}}
            """);
        assertEquals(CronJitterConfig.DEFAULT,
            CronFeatureGate.evaluate(Map.of(), invalid).jitterConfig());

        var excessiveCacheLead = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{"tengu_kairos_cron_config":{
              "recurringFrac":0.25,"recurringCapMs":1234,
              "oneShotMaxMs":5000,"oneShotFloorMs":1000,
              "oneShotMinuteMod":15,"recurringMaxAgeMs":6000,
              "cacheLeadMs":60001
            }}}
            """);
        assertEquals(CronJitterConfig.DEFAULT,
            CronFeatureGate.evaluate(Map.of(), excessiveCacheLead).jitterConfig());
    }
}
