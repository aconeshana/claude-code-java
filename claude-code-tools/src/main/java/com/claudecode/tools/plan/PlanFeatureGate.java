package com.claudecode.tools.plan;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import java.util.Map;

/** Environment gate for the Java multi-plan extension. */
public final class PlanFeatureGate {

    public static final String MULTI_PLAN_ENV = "CLAUDE_CODE_ENABLE_MULTI_PLAN";

    private PlanFeatureGate() {}

    @Explanation("Multi-plan storage is a Java opt-in extension; the default preserves the released single-plan-per-session behavior.")
    public static boolean systemEnabled() {
        return evaluate(SubprocessEnvironment.snapshot());
    }

    static boolean evaluate(Map<String, String> environment) {
        return environment != null
            && EnvUtils.isEnvTruthy(environment.get(MULTI_PLAN_ENV));
    }
}
