package com.claudecode.tools.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanFeatureGateTest {

    @Test
    void multiPlanIsOptInAndUsesSharedTruthyValues() {
        assertFalse(PlanFeatureGate.evaluate(Map.of()));
        assertFalse(PlanFeatureGate.evaluate(Map.of(PlanFeatureGate.MULTI_PLAN_ENV, "0")));
        assertFalse(PlanFeatureGate.evaluate(Map.of(PlanFeatureGate.MULTI_PLAN_ENV, "false")));

        assertTrue(PlanFeatureGate.evaluate(Map.of(PlanFeatureGate.MULTI_PLAN_ENV, "1")));
        assertTrue(PlanFeatureGate.evaluate(Map.of(PlanFeatureGate.MULTI_PLAN_ENV, "true")));
        assertTrue(PlanFeatureGate.evaluate(Map.of(PlanFeatureGate.MULTI_PLAN_ENV, "yes")));
        assertTrue(PlanFeatureGate.evaluate(Map.of(PlanFeatureGate.MULTI_PLAN_ENV, "on")));
    }
}
