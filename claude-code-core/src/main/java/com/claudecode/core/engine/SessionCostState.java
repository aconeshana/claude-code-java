package com.claudecode.core.engine;


import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.message.Usage;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


public final class SessionCostState {

    private static final SessionCostState INSTANCE = new SessionCostState();


    public static SessionCostState get() { return INSTANCE; }

    private SessionCostState() {}

    private volatile long startTimeMs = System.currentTimeMillis();
    private final AtomicLong totalApiDurationMs = new AtomicLong();
    private final AtomicLong totalApiDurationWithoutRetriesMs = new AtomicLong();
    private final AtomicLong totalToolDurationMs = new AtomicLong();
    private final AtomicLong totalLinesAdded = new AtomicLong();
    private final AtomicLong totalLinesRemoved = new AtomicLong();
    /** model → cumulative usage. Insertion-ordered snapshot on read. */
    private final Map<String, Usage> usageByModel = new ConcurrentHashMap<>();
    /** model → cost accumulated in API request completion order. */
    private final Map<String, Double> costByModel = new ConcurrentHashMap<>();
    private final Object costLock = new Object();
    private double totalCostUsd;

    /**
     * Records one completed API request.
     */
    public void recordApiRequest(String model, Usage usage, long durationMs) {
        recordApiRequest(model, usage, durationMs, durationMs);
    }

    /**
     * Records one successful API request with both.
     */
    public void recordApiRequest(String model, Usage usage, long durationIncludingRetriesMs,
                                 long durationWithoutRetriesMs) {
        if (durationIncludingRetriesMs > 0) {
            totalApiDurationMs.addAndGet(durationIncludingRetriesMs);
        }
        if (durationWithoutRetriesMs > 0) {
            totalApiDurationWithoutRetriesMs.addAndGet(durationWithoutRetriesMs);
        }
        if (usage != null && model != null && !StringUtils.isBlank(model)) {
            double requestCost = CostCalculator.forModel(model).calculateCost(usage);
            synchronized (costLock) {
                usageByModel.merge(model, usage, Usage::add);
                costByModel.merge(model, requestCost, Double::sum);
                totalCostUsd += requestCost;
            }
        }
    }

    /** Adds one actual tool-call duration; permission and hook waiting is excluded. */
    public void recordToolDuration(long durationMs) {
        if (durationMs > 0) totalToolDurationMs.addAndGet(durationMs);
    }

/**
     * Records a file edit's line delta.
     */
    public void recordLinesChanged(long added, long removed) {
        if (added > 0) totalLinesAdded.addAndGet(added);
        if (removed > 0) totalLinesRemoved.addAndGet(removed);
    }


    public long wallDurationMs() { return System.currentTimeMillis() - startTimeMs; }


    public long apiDurationMs() { return totalApiDurationMs.get(); }

    /** Cumulative final successful API-attempt time, excluding retries/backoff. */
    public long apiDurationWithoutRetriesMs() {
        return totalApiDurationWithoutRetriesMs.get();
    }

    /** Sum of individual tool-call elapsed times (parallel calls may exceed wall time). */
    public long toolDurationMs() { return totalToolDurationMs.get(); }

    public long totalLinesAdded() { return totalLinesAdded.get(); }

    public long totalLinesRemoved() { return totalLinesRemoved.get(); }


    public Map<String, Usage> usageByModel() {
        return new LinkedHashMap<>(usageByModel);
    }


    public Map<String, Usage> liveUsageByModel() {
        return Collections.unmodifiableMap(usageByModel);
    }

    /** Snapshot of per-model costs accumulated in request completion order. */
    public Map<String, Double> costByModel() {
        synchronized (costLock) {
            return new LinkedHashMap<>(costByModel);
        }
    }

    /**
     * Read-only live cost view, paired with {@link #liveUsageByModel} for held
     * SDK result envelopes.
     */
    public Map<String, Double> liveCostByModel() {
        return Collections.unmodifiableMap(costByModel);
    }

/**
     * Exact process-cumulative cost using.
     */
    public double totalCostUsd() {
        synchronized (costLock) {
            return totalCostUsd;
        }
    }

    /** True when nothing has been recorded — {@code /cost} shows the zero-usage line. */
    public boolean isEmpty() { return usageByModel.isEmpty(); }

    /**
     * Resets all counters and the wall-clock start.
     */
    public void reset() {
        startTimeMs = System.currentTimeMillis();
        totalApiDurationMs.set(0);
        totalApiDurationWithoutRetriesMs.set(0);
        totalToolDurationMs.set(0);
        totalLinesAdded.set(0);
        totalLinesRemoved.set(0);
        usageByModel.clear();
        synchronized (costLock) {
            costByModel.clear();
            totalCostUsd = 0.0;
        }
    }


    public record Snapshot(
        long apiDurationMs,
        long apiDurationWithoutRetriesMs,
        long toolDurationMs,
        long wallDurationMs,
        long linesAdded,
        long linesRemoved,
        Map<String, Usage> usageByModel,
        Map<String, Double> costByModel,
        double totalCostUsd
    ) {
        public Snapshot {
            usageByModel = immutableCopy(usageByModel);
            costByModel = immutableCopy(costByModel);
        }

        /** Compatibility shape predating persisted per-model costs. */
        public Snapshot(long apiDurationMs, long apiDurationWithoutRetriesMs,
                        long toolDurationMs, long wallDurationMs,
                        long linesAdded, long linesRemoved,
                        Map<String, Usage> usageByModel) {
            this(apiDurationMs, apiDurationWithoutRetriesMs, toolDurationMs,
                wallDurationMs, linesAdded, linesRemoved, usageByModel,
                calculateCosts(usageByModel), calculateTotalCost(usageByModel));
        }

        /** Compatibility shape for callers compiled against the earlier snapshot. */
        public Snapshot(long apiDurationMs, long wallDurationMs, long linesAdded,
                        long linesRemoved, Map<String, Usage> usageByModel) {
            this(apiDurationMs, apiDurationMs, 0L, wallDurationMs,
                linesAdded, linesRemoved, usageByModel);
        }
    }


    public Snapshot snapshot() {
        synchronized (costLock) {
            return new Snapshot(apiDurationMs(), apiDurationWithoutRetriesMs(), toolDurationMs(),
                wallDurationMs(), totalLinesAdded(), totalLinesRemoved(),
                new LinkedHashMap<>(usageByModel),
                new LinkedHashMap<>(costByModel), totalCostUsd);
        }
    }


    public void restore(Snapshot s) {
        if (s == null) return;
        if (s.wallDurationMs() > 0) {
            startTimeMs = System.currentTimeMillis() - s.wallDurationMs();
        }
        totalApiDurationMs.set(Math.max(0, s.apiDurationMs()));
        totalApiDurationWithoutRetriesMs.set(Math.max(0, s.apiDurationWithoutRetriesMs()));
        totalToolDurationMs.set(Math.max(0, s.toolDurationMs()));
        totalLinesAdded.set(Math.max(0, s.linesAdded()));
        totalLinesRemoved.set(Math.max(0, s.linesRemoved()));
        usageByModel.clear();
        synchronized (costLock) {
            costByModel.clear();
            if (s.usageByModel() != null) {
                usageByModel.putAll(s.usageByModel());
            }
            if (s.costByModel() != null && !s.costByModel().isEmpty()) {
                costByModel.putAll(s.costByModel());
            } else {
                costByModel.putAll(calculateCosts(s.usageByModel()));
            }
            totalCostUsd = s.totalCostUsd();
        }
    }

    private static Map<String, Double> calculateCosts(Map<String, Usage> usageByModel) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (usageByModel == null) return result;
        for (Map.Entry<String, Usage> entry : usageByModel.entrySet()) {
            result.put(entry.getKey(), CostCalculator.forModel(entry.getKey())
                .calculateCost(entry.getValue()));
        }
        return result;
    }

    private static double calculateTotalCost(Map<String, Usage> usageByModel) {
        double result = 0.0;
        for (double cost : calculateCosts(usageByModel).values()) result += cost;
        return result;
    }

    private static <K, V> Map<K, V> immutableCopy(Map<K, V> source) {
        if (source == null || source.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
