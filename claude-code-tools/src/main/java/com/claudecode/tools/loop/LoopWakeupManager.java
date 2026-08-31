package com.claudecode.tools.loop;

import org.apache.commons.lang3.Strings;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import com.claudecode.tools.cron.CronStore;
import com.claudecode.tools.cron.CronJitterConfig;


public final class LoopWakeupManager {

    public static final int MIN_DELAY_SECONDS = 60;
    public static final int MAX_DELAY_SECONDS = 3_600;
    public static final int KEEPALIVE_DELAY_SECONDS = 1_200;
    public static final long DEFAULT_MAX_AGE_MS = CronJitterConfig.DEFAULT.recurringMaxAgeMs();
    public static final long DEFAULT_CACHE_LEAD_MS = CronJitterConfig.DEFAULT.cacheLeadMs();

    private static final LoopWakeupManager GLOBAL = new LoopWakeupManager(
        () -> LoopFeatureGate.system().dynamicEnabled(),
        () -> LoopFeatureGate.system().keepaliveEnabled(),
        System::currentTimeMillis,
        ZoneId.systemDefault(),
        DEFAULT_MAX_AGE_MS,
        DEFAULT_CACHE_LEAD_MS,
        () -> ThreadLocalRandom.current().nextLong(0xffff_ffffL));

    private final BooleanSupplier dynamicEnabled;
    private final BooleanSupplier keepaliveEnabled;
    private final LongSupplier nowMillis;
    private final ZoneId zoneId;
    private final long recurringMaxAgeMs;
    private final long cacheLeadMs;
    private final LongSupplier randomUnsigned;
    private final Map<String, LoopState> states = new ConcurrentHashMap<>();
    private final AtomicReference<String> inFlightLoopPrompt = new AtomicReference<>();
    private final AtomicInteger keepaliveCount = new AtomicInteger();

    public LoopWakeupManager(BooleanSupplier dynamicEnabled, BooleanSupplier keepaliveEnabled,
                      LongSupplier nowMillis, ZoneId zoneId, long recurringMaxAgeMs,
                      long cacheLeadMs, LongSupplier randomUnsigned) {
        this.dynamicEnabled = dynamicEnabled;
        this.keepaliveEnabled = keepaliveEnabled;
        this.nowMillis = nowMillis;
        this.zoneId = zoneId;
        this.recurringMaxAgeMs = recurringMaxAgeMs;
        this.cacheLeadMs = cacheLeadMs;
        this.randomUnsigned = randomUnsigned;
    }

    public static LoopWakeupManager global() { return GLOBAL; }

    public static LoopWakeupManager disabled() {
        return new LoopWakeupManager(() -> false, () -> false,
            System::currentTimeMillis, ZoneId.systemDefault(), DEFAULT_MAX_AGE_MS,
            DEFAULT_CACHE_LEAD_MS, () -> 0);
    }

    public ScheduleResult schedule(double delaySeconds, String prompt, String reason) {
        if (!dynamicEnabled.getAsBoolean()) return null;
        return scheduleInternal(delaySeconds, prompt, false);
    }

    private synchronized ScheduleResult scheduleInternal(double delaySeconds, String prompt,
                                                         boolean viaKeepalive) {
        if (!viaKeepalive) keepaliveCount.set(0);
        CronStore.removeWhere(job -> Strings.CS.equals("loop", job.kind()));

        long now = nowMillis.getAsLong();
        LoopState prior = states.get(prompt);
        boolean stale = prior != null
            && now > prior.lastScheduledFor() + MAX_DELAY_SECONDS * 1_000L;
        long startedAt = prior == null || stale ? now : prior.startedAt();
        if (recurringMaxAgeMs > 0 && now - startedAt >= recurringMaxAgeMs) {
            if (!prior.agedOut()) {
                states.put(prompt, new LoopState(startedAt,
                    now - (MAX_DELAY_SECONDS - MIN_DELAY_SECONDS) * 1_000L, true));
            }
            return null;
        }

        NormalizedDelay normalized = normalize(delaySeconds, now);
        ZonedDateTime target = Instant.ofEpochMilli(normalized.targetMs()).atZone(zoneId);
        String cron = target.getMinute() + " " + target.getHour() + " * * *";
        String id = String.format("%08x", randomUnsigned.getAsLong() & 0xffff_ffffL);
        CronStore.add(cron, prompt, false, false, null,
            normalized.createdAt(), "loop", id);
        states.put(prompt, new LoopState(startedAt, normalized.targetMs(), false));
        if (viaKeepalive) keepaliveCount.incrementAndGet();
        return new ScheduleResult(normalized.targetMs(), normalized.clampedSeconds(),
            normalized.wasClamped());
    }

    private NormalizedDelay normalize(double requested, long now) {
        long rounded;
        if (Double.isNaN(requested) || requested == Double.NEGATIVE_INFINITY) {
            rounded = MIN_DELAY_SECONDS;
        } else if (requested == Double.POSITIVE_INFINITY) {
            rounded = MAX_DELAY_SECONDS;
        } else {
            rounded = Math.round(requested);
        }
        int clamped = (int) Math.max(MIN_DELAY_SECONDS,
            Math.min(MAX_DELAY_SECONDS, rounded));
        boolean wasClamped = !Double.isFinite(requested) || rounded != clamped;
        long rawTarget = now + clamped * 1_000L;
        long target = ceilToMinute(rawTarget);
        if (cacheLeadMs > 0 && clamped * 1_000L <= 300_000L) {
            long warmLimit = 300_000L - cacheLeadMs;
            while (target - now > warmLimit
                    && target - 60_000L >= now + MIN_DELAY_SECONDS * 1_000L) {
                target -= 60_000L;
            }
        }
        long createdAt = rawTarget < target ? rawTarget : target - 1;
        return new NormalizedDelay(clamped, wasClamped, target, createdAt);
    }

    private static long ceilToMinute(long epochMillis) {
        long minute = Math.floorDiv(epochMillis, 60_000L) * 60_000L;
        return epochMillis == minute ? minute : minute + 60_000L;
    }

    public void markLoopTaskFired(String prompt) {
        inFlightLoopPrompt.set(prompt);
    }

    /** Called after a fired loop turn becomes idle; may arm one 20-minute fallback. */
    public synchronized ScheduleResult onTurnIdle() {
        String prompt = inFlightLoopPrompt.getAndSet(null);
        if (prompt == null || !keepaliveEnabled.getAsBoolean() || hasPendingLoopJobs()) {
            return null;
        }
        if (!dynamicEnabled.getAsBoolean() || keepaliveCount.get() >= 1) return null;
        return scheduleInternal(KEEPALIVE_DELAY_SECONDS, prompt, true);
    }

    public boolean hasPendingLoopJobs() {
        return CronStore.list().stream().anyMatch(job -> Strings.CS.equals("loop", job.kind()));
    }

    public synchronized int cancelAll() {
        var loopPrompts = CronStore.list().stream()
            .filter(job -> Strings.CS.equals("loop", job.kind()))
            .map(CronStore.CronJob::prompt)
            .toList();
        int removed = CronStore.removeWhere(job -> Strings.CS.equals("loop", job.kind()));
        loopPrompts.forEach(states::remove);
        String inFlight = inFlightLoopPrompt.getAndSet(null);
        if (inFlight != null) states.remove(inFlight);
        keepaliveCount.set(0);
        return removed;
    }

    public long nowMillis() { return nowMillis.getAsLong(); }
    public ZoneId zoneId() { return zoneId; }

    public record ScheduleResult(long scheduledFor, int clampedDelaySeconds,
                                 boolean wasClamped) { }

    private record LoopState(long startedAt, long lastScheduledFor, boolean agedOut) { }
    private record NormalizedDelay(int clampedSeconds, boolean wasClamped,
                                   long targetMs, long createdAt) { }
}
