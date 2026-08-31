package com.claudecode.services.telemetry;

import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-wide holder for the active {@link TelemetryProvider} and owner of its lifecycle
 * (initialize / flush / shutdown).
 */
public final class Telemetry {

    private static final Logger log = LoggerFactory.getLogger(Telemetry.class);

    private static volatile TelemetryProvider active = TelemetryProvider.noOp();

    private Telemetry() {}

    /**
     * Initializes telemetry from the process environment.
     */
    public static void initialize(boolean allowConsole) {
        initialize(SubprocessEnvironment.snapshot(), allowConsole);
    }

    /** Package-private variant that injects a synthetic environment (tests). */
    static void initialize(Map<String, String> env, boolean allowConsole) {
        if (isEnabled(env)) {
            try {
                active = new OtelTelemetryProvider(env, allowConsole);
            } catch (Throwable t) {
                active = TelemetryProvider.noOp();
                log.warn("Telemetry initialization failed; telemetry disabled", t);
            }
        } else {
            active = TelemetryProvider.noOp();
        }
    }

    public static TelemetryProvider instance() {
        return active;
    }

    public static void flush() {
        active.flush();
    }

    public static void shutdown() {
        active.shutdown();
    }

    static boolean isEnabled(Map<String, String> env) {
        return EnvUtils.isEnvTruthy(
            env.get("CLAUDE_CODE_ENABLE_TELEMETRY"));
    }
}
