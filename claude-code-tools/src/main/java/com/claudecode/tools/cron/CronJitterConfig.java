package com.claudecode.tools.cron;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;


public record CronJitterConfig(
    double recurringFrac,
    long recurringCapMs,
    long oneShotMaxMs,
    long oneShotFloorMs,
    int oneShotMinuteMod,
    long recurringMaxAgeMs,
    long cacheLeadMs) {

    public static final CronJitterConfig DEFAULT = new CronJitterConfig(
        0.50d,
        30 * 60 * 1_000L,
        90 * 1_000L,
        0L,
        30,
        7L * 24 * 60 * 60 * 1_000L,
        15_000L);

    private static final long HALF_HOUR_MS = 30L * 60 * 1_000L;
    private static final long THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1_000L;
    private static final long MAX_CACHE_LEAD_MS = 60_000L;

    public CronJitterConfig {
        recurringFrac = Math.max(0d, recurringFrac);
        recurringCapMs = Math.max(0L, recurringCapMs);
        oneShotMaxMs = Math.max(0L, oneShotMaxMs);
        oneShotFloorMs = Math.max(0L, Math.min(oneShotFloorMs, oneShotMaxMs));
        oneShotMinuteMod = Math.max(1, oneShotMinuteMod);
        recurringMaxAgeMs = Math.max(0L, recurringMaxAgeMs);
        cacheLeadMs = Math.max(0L, cacheLeadMs);
    }

    



    public static CronJitterConfig from(JsonNode raw) {
        if (raw == null || !raw.isObject()) return DEFAULT;
        JsonNode recurringFrac = raw.get("recurringFrac");
        JsonNode recurringCapMs = raw.get("recurringCapMs");
        JsonNode oneShotMaxMs = raw.get("oneShotMaxMs");
        JsonNode oneShotFloorMs = raw.get("oneShotFloorMs");
        JsonNode oneShotMinuteMod = raw.get("oneShotMinuteMod");
        JsonNode recurringMaxAgeMs = raw.get("recurringMaxAgeMs");
        JsonNode cacheLeadMs = raw.get("cacheLeadMs");
        if (recurringMaxAgeMs == null) {
            recurringMaxAgeMs = JsonNodeFactoryHolder.longNode(DEFAULT.recurringMaxAgeMs());
        }
        if (cacheLeadMs == null) {
            cacheLeadMs = JsonNodeFactoryHolder.longNode(DEFAULT.cacheLeadMs());
        }
        if (recurringFrac == null || !recurringFrac.isNumber()
                || recurringCapMs == null || !recurringCapMs.canConvertToLong()
                || oneShotMaxMs == null || !oneShotMaxMs.canConvertToLong()
                || oneShotFloorMs == null || !oneShotFloorMs.canConvertToLong()
                || oneShotMinuteMod == null || !oneShotMinuteMod.canConvertToInt()
                || !recurringMaxAgeMs.canConvertToLong()
                || !cacheLeadMs.canConvertToLong()) return DEFAULT;
        double frac = recurringFrac.asDouble();
        long cap = recurringCapMs.asLong();
        long max = oneShotMaxMs.asLong();
        long floor = oneShotFloorMs.asLong();
        int mod = oneShotMinuteMod.asInt();
        long age = recurringMaxAgeMs.asLong();
        long lead = cacheLeadMs.asLong();
        if (!Double.isFinite(frac) || frac < 0d || frac > 1d
                || cap < 0 || cap > HALF_HOUR_MS
                || max < 0 || max > HALF_HOUR_MS
                || floor < 0 || floor > HALF_HOUR_MS || floor > max
                || mod < 1 || mod > 60
                || age < 0 || age > THIRTY_DAYS_MS
                || lead < 0 || lead > MAX_CACHE_LEAD_MS) return DEFAULT;
        return new CronJitterConfig(frac, cap, max, floor, mod, age, lead);
    }

    /** Tiny dependency-free Jackson node factory for defaulted fields. */
    private static final class JsonNodeFactoryHolder {
        private static JsonNode longNode(long value) {
            return JsonNodeFactory.instance.numberNode(value);
        }
    }
}
