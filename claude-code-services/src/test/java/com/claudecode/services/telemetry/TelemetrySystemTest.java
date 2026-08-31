package com.claudecode.services.telemetry;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelemetrySystemTest {

    // --- AnalyticsSink + SinkKillswitch ---

    @Test
    void sinkIsActiveByDefault() {
        var sink = new AnalyticsSink();
        assertTrue(sink.isActive());
    }

    @Test
    void killswitchDeactivatesSink() {
        var sink = new AnalyticsSink();
        var killswitch = sink.getKillswitch();

        assertFalse(killswitch.isTriggered());
        killswitch.trigger();
        assertTrue(killswitch.isTriggered());
        assertFalse(sink.isActive());

        killswitch.reset();
        assertFalse(killswitch.isTriggered());
        assertTrue(sink.isActive());
    }

    @Test
    void sinkRejectsEventsWhenInactive() {
        var sink = new AnalyticsSink();
        sink.deactivate();

        var event = new AnalyticsService.AnalyticsEvent("test", Map.of(), Instant.now());
        assertFalse(sink.send(event));
    }

    // --- AnalyticsService ---

    @Test
    void analyticsServiceRecordsEvents() {
        var sink = new AnalyticsSink();
        var service = new AnalyticsService(sink, AnalyticsService.PrivacyLevel.STANDARD);

        assertTrue(service.recordEvent("tool.used", Map.of("tool", "Bash")));
    }

    @Test
    void analyticsServiceRespectsPrivacyDisabled() {
        var sink = new AnalyticsSink();
        var service = new AnalyticsService(sink, AnalyticsService.PrivacyLevel.DISABLED);

        assertFalse(service.recordEvent("tool.used", Map.of()));
    }

    @Test
    void analyticsServiceMinimalPrivacy() {
        var sink = new AnalyticsSink();
        var service = new AnalyticsService(sink, AnalyticsService.PrivacyLevel.MINIMAL);

        assertTrue(service.recordEvent("session.start", Map.of()));
        assertTrue(service.recordEvent("error.api", Map.of()));
        assertFalse(service.recordEvent("tool.used", Map.of()));
    }

    // --- TelemetryProvider ---

    @Test
    void noOpTelemetryProviderIsDisabled() {
        var provider = TelemetryProvider.noOp();
        assertFalse(provider.isEnabled());
        // Should not throw
        provider.recordSpan("test", Map.of());
        provider.recordMetric("test", 1.0, Map.of());
        provider.recordLog("INFO", "test");
        provider.flush();
        provider.shutdown();
    }

    // --- Telemetry holder + OtelTelemetryProvider ---

    @Test
    void telemetryDisabledByDefault() {
        // No CLAUDE_CODE_ENABLE_TELEMETRY → no-op provider, nothing exported.
        Telemetry.initialize(Map.of(), false);
        TelemetryProvider provider = Telemetry.instance();
        assertFalse(provider.isEnabled());
        assertDoesNotThrow(() -> {
            provider.recordSpan("s", Map.of("k", "v"));
            provider.recordMetric("m", 1.0, Map.of());
            provider.recordLog("INFO", "msg");
            provider.flush();
            provider.shutdown();
        });
    }

    @Test
    void otelProviderEnabledWithConsoleExporters() {
        // Enabled + console exporters: real OTEL provider, emit paths exercised.
        Telemetry.initialize(Map.of(
            "CLAUDE_CODE_ENABLE_TELEMETRY", "true",
            "OTEL_METRICS_EXPORTER", "console",
            "OTEL_LOGS_EXPORTER", "console",
            "OTEL_TRACES_EXPORTER", "console"), true);
        TelemetryProvider provider = Telemetry.instance();
        assertTrue(provider.isEnabled());
        assertDoesNotThrow(() -> {
            provider.recordSpan("span", Map.of("env", "test"));
            provider.recordMetric("metric", 2.5, Map.of("unit", "count"));
            provider.recordLog("WARN", "hello");
            provider.flush();
            provider.shutdown();
        });
    }

    @Test
    void otelProviderStripsConsoleWhenDisallowed() {
// allowConsole=false strips console exporters → no exporter wired, but emitting
// must still not throw.
        Telemetry.initialize(Map.of(
            "CLAUDE_CODE_ENABLE_TELEMETRY", "true",
            "OTEL_METRICS_EXPORTER", "console"), false);
        TelemetryProvider provider = Telemetry.instance();
        assertTrue(provider.isEnabled());
        assertDoesNotThrow(() -> {
            provider.recordMetric("metric", 1.0, Map.of());
            provider.flush();
            provider.shutdown();
        });
    }

}
