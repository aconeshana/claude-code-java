package com.claudecode.tools.cron;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * Read-only projection of active cron jobs for lifecycle hook payloads.
 */
public final class ScheduledTaskHooks {

    private static final int MAX_PROMPT_CHARS = 1_000;

    private ScheduledTaskHooks() {}

    public static List<Map<String, Object>> snapshot() {
        return CronStore.list().stream()
            .filter(job -> !job.durable())
            .map(job -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", job.id());
            item.put("schedule", job.cron());
            item.put("recurring", job.recurring());
            item.put("prompt", clip(job.prompt()));
            return Collections.unmodifiableMap(item);
            }).toList();
    }

    private static String clip(String value) {
        if (value.length() <= MAX_PROMPT_CHARS) return value;
        int end = MAX_PROMPT_CHARS;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        String prefix = value.substring(0, end);
        return prefix + "… [+" + (value.length() - prefix.length()) + " chars]";
    }
}
