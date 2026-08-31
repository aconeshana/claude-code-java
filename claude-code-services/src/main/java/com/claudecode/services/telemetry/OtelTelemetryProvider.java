package com.claudecode.services.telemetry;

import java.util.Locale;

import org.apache.commons.lang3.Strings;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.exporter.logging.SystemOutLogRecordExporter;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import com.claudecode.core.config.VersionInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link TelemetryProvider} backed by the OpenTelemetry SDK.
 */
final class OtelTelemetryProvider implements TelemetryProvider {

// service.version resource attribute for telemetry.
    private static final String SERVICE_VERSION = VersionInfo.version();
    private static final long SHUTDOWN_TIMEOUT_MS = 2000;
    private static final long METRICS_INTERVAL_MS = 60000;
    private static final long LOGS_INTERVAL_MS = 5000;
    private static final long TRACES_INTERVAL_MS = 5000;

    private final SdkMeterProvider meterProvider;
    private final SdkLoggerProvider loggerProvider;
    private final SdkTracerProvider tracerProvider;
    private final Meter meter;
    private final Logger logger;
    private final Tracer tracer;
    private final Map<String, DoubleCounter> counters = new ConcurrentHashMap<>();

    OtelTelemetryProvider(Map<String, String> env, boolean allowConsole) {
        Resource resource = Resource.builder()
            .put("service.name", "claude-code")
            .put("service.version", SERVICE_VERSION)
            .build();

        List<String> metricExporters = parseExporters(env.get("OTEL_METRICS_EXPORTER"), allowConsole);
        List<String> logExporters = parseExporters(env.get("OTEL_LOGS_EXPORTER"), allowConsole);
        List<String> traceExporters = parseExporters(env.get("OTEL_TRACES_EXPORTER"), allowConsole);

        String endpoint = env.getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");
        Map<String, String> headers = parseHeaders(env.get("OTEL_EXPORTER_OTLP_HEADERS"));
        boolean grpc = isGrpc(env.get("OTEL_EXPORTER_OTLP_PROTOCOL"));

        // --- Metrics ---
        var meterBuilder = SdkMeterProvider.builder().setResource(resource);
        for (String type : metricExporters) {
            MetricReader reader = buildMetricReader(type, grpc, endpoint, headers);
            if (reader != null) {
                meterBuilder.registerMetricReader(reader);
            }
        }
        this.meterProvider = meterBuilder.build();
        this.meter = meterProvider.get("com.claudecode");

        // --- Logs ---
        SdkLoggerProvider loggerProviderLocal = null;
        List<LogRecordExporter> logExportersList = buildLogExporters(logExporters, grpc, endpoint, headers);
        if (!logExportersList.isEmpty()) {
            var loggerBuilder = SdkLoggerProvider.builder().setResource(resource);
            for (LogRecordExporter exporter : logExportersList) {
                loggerBuilder.addLogRecordProcessor(BatchLogRecordProcessor.builder(exporter)
                    .setScheduleDelay(LOGS_INTERVAL_MS, TimeUnit.MILLISECONDS).build());
            }
            loggerProviderLocal = loggerBuilder.build();
        }
        this.loggerProvider = loggerProviderLocal;
        this.logger = loggerProvider != null ? loggerProvider.get("com.claudecode") : null;

        // --- Traces ---
        SdkTracerProvider tracerProviderLocal = null;
        List<SpanExporter> spanExporters = buildSpanExporters(traceExporters, grpc, endpoint, headers);
        if (!spanExporters.isEmpty()) {
            var tracerBuilder = SdkTracerProvider.builder().setResource(resource);
            for (SpanExporter exporter : spanExporters) {
                tracerBuilder.addSpanProcessor(BatchSpanProcessor.builder(exporter)
                    .setScheduleDelay(TRACES_INTERVAL_MS, TimeUnit.MILLISECONDS).build());
            }
            tracerProviderLocal = tracerBuilder.build();
        }
        this.tracerProvider = tracerProviderLocal;
        this.tracer = tracerProvider != null ? tracerProvider.get("com.claudecode") : null;
    }

    @Override
    public void recordSpan(String name, Map<String, String> attributes) {
        if (tracer == null) {
            return;
        }
        Span span = tracer.spanBuilder(name).startSpan();
        span.setAllAttributes(toAttributes(attributes));
        span.end();
    }

    @Override
    public void recordMetric(String name, double value, Map<String, String> tags) {
        if (meter == null) {
            return;
        }
        DoubleCounter counter = counters.computeIfAbsent(name, n -> meter.counterBuilder(n).ofDoubles().build());
        counter.add(value, toAttributes(tags));
    }

    @Override
    public void recordLog(String level, String message) {
        if (logger == null) {
            return;
        }
        logger.logRecordBuilder()
            .setSeverityText(level)
            .setBody(message)
            .emit();
    }

    @Override
    public void flush() {
        runWithTimeout(() -> {
            if (meterProvider != null) {
                meterProvider.forceFlush();
            }
            if (loggerProvider != null) {
                loggerProvider.forceFlush();
            }
            if (tracerProvider != null) {
                tracerProvider.forceFlush();
            }
        });
    }

    @Override
    public void shutdown() {
        runWithTimeout(() -> {
            if (meterProvider != null) {
                meterProvider.shutdown();
            }
            if (loggerProvider != null) {
                loggerProvider.shutdown();
            }
            if (tracerProvider != null) {
                tracerProvider.shutdown();
            }
        });
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    // --- helpers ---

    private static List<String> parseExporters(String value, boolean allowConsole) {
        if (value == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || Strings.CS.equals(trimmed, "none")) {
                continue;
            }
            if (!allowConsole && Strings.CS.equals(trimmed, "console")) {
                continue;
            }
            result.add(trimmed);
        }
        return result;
    }

    private static Map<String, String> parseHeaders(String value) {
        Map<String, String> headers = new HashMap<>();
        if (value == null) {
            return headers;
        }
        for (String pair : value.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                headers.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return headers;
    }

    private static boolean isGrpc(String protocol) {
        String p = protocol == null ? "" : protocol.trim().toLowerCase(Locale.ROOT);
        // Unset or "grpc" → gRPC OTLP. HTTP/protobuf & http/json need the
        // okhttp transport which is not bundled in this build.
        return p.isEmpty() || Strings.CS.equals(p, "grpc");
    }

    /**
     * Builds a reader whose ownership is immediately transferred to
     * the meter provider builder via {@code registerMetricReader(reader)}. The
     * resulting provider invokes {@link MetricReader#shutdown} from its shutdown;
     * closing the reader in this factory would disable metrics immediately.
     */
    private static MetricReader buildMetricReader(String type, boolean grpc,
                                                   String endpoint,
                                                   Map<String, String> headers) {
        return switch (type) {
            case "console" -> PeriodicMetricReader.builder(LoggingMetricExporter.create())
                .setInterval(METRICS_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .build();
            case "otlp" -> grpc
                ? PeriodicMetricReader.builder(buildOtlpMetricExporter(endpoint, headers))
                    .setInterval(METRICS_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .build()
                : null;
            default -> null;
        };
    }

    private static OtlpGrpcMetricExporter buildOtlpMetricExporter(String endpoint, Map<String, String> headers) {
        var builder = OtlpGrpcMetricExporter.builder().setEndpoint(endpoint);
        headers.forEach(builder::addHeader);
        return builder.build();
    }

    private static OtlpGrpcLogRecordExporter buildOtlpLogExporter(String endpoint, Map<String, String> headers) {
        var builder = OtlpGrpcLogRecordExporter.builder().setEndpoint(endpoint);
        headers.forEach(builder::addHeader);
        return builder.build();
    }

    private static OtlpGrpcSpanExporter buildOtlpSpanExporter(String endpoint, Map<String, String> headers) {
        var builder = OtlpGrpcSpanExporter.builder().setEndpoint(endpoint);
        headers.forEach(builder::addHeader);
        return builder.build();
    }

    private static List<LogRecordExporter> buildLogExporters(
        List<String> types, boolean grpc, String endpoint, Map<String, String> headers) {
        List<LogRecordExporter> exporters = new ArrayList<>();
        for (String type : types) {
            switch (type) {
                case "console" -> exporters.add(SystemOutLogRecordExporter.create());
                case "otlp" -> {
                    if (grpc) {
                        exporters.add(buildOtlpLogExporter(endpoint, headers));
                    }
                }
                default -> { /* unsupported type: ignored */ }
            }
        }
        return exporters;
    }

    private static List<SpanExporter> buildSpanExporters(
        List<String> types, boolean grpc, String endpoint, Map<String, String> headers) {
        List<SpanExporter> exporters = new ArrayList<>();
        for (String type : types) {
            switch (type) {
                case "console" -> exporters.add(LoggingSpanExporter.create());
                case "otlp" -> {
                    if (grpc) {
                        exporters.add(buildOtlpSpanExporter(endpoint, headers));
                    }
                }
                default -> { /* unsupported type: ignored */ }
            }
        }
        return exporters;
    }

    private static Attributes toAttributes(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return Attributes.empty();
        }
        AttributesBuilder builder = Attributes.builder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            builder.put(AttributeKey.stringKey(entry.getKey()), entry.getValue());
        }
        return builder.build();
    }

    private static void runWithTimeout(Runnable action) {
        Thread t = new Thread(action);
        t.setDaemon(true);
        t.start();
        try {
            t.join(SHUTDOWN_TIMEOUT_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
