package com.claudecode.tools.cron;

import org.apache.commons.lang3.Strings;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cron expression parsing, next-run calculation, and human-readable formatting.
 */
final class CronUtils {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern UNSIGNED_INTEGER = Pattern.compile("\\d+");
    private static final Pattern SINGLE_DIGIT = Pattern.compile("\\d");
    private static final Pattern EVERY_N = Pattern.compile("^\\*/(\\d+)$");
    private static final Pattern WILDCARD_STEP = Pattern.compile("^\\*(?:/(\\d+))?$");
    private static final Pattern RANGE_STEP =
        Pattern.compile("^(\\d+)-(\\d+)(?:/(\\d+))?$");

    private static final int[][] FIELD_RANGES = {
        {0, 59},  // minute
        {0, 23},  // hour
        {1, 31},  // dayOfMonth
        {1, 12},  // month
        {0, 6},   // dayOfWeek (0=Sunday; 7 accepted as alias)
    };

    private static final String[] DAY_NAMES = {
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
    };

    private CronUtils() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns true if the 5-field cron expression is syntactically valid. */
    static boolean isValid(String expr) {
        return parse(expr) != null;
    }

    /**
     * Computes the next fire time (epoch-millis) strictly after now.
     */
    static Long nextRunMs(String expr) {
        return nextRunAfterMs(expr, System.currentTimeMillis());
    }

    /**
     * Computes the next fire time (epoch-millis) strictly after {@code fromEpochMs}.
     * Used by {@link CronScheduler} to anchor recurring jobs from their {@code createdAt}.
     */
    static Long nextRunAfterMs(String expr, long fromEpochMs) {
        int[][] fields = parse(expr);
        if (fields == null) return null;
        LocalDateTime from = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(fromEpochMs), ZoneId.systemDefault());
        LocalDateTime next = computeNext(fields, from);
        return next == null ? null : next
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * Convert a cron expression to a human-readable string.
     */
    static String toHuman(String cron) {
        String[] p = WHITESPACE.split(cron.trim());
        if (p.length != 5) return cron;

        String minute    = p[0];
        String hour      = p[1];
        String dayOfMonth = p[2];
        String month     = p[3];
        String dayOfWeek = p[4];

        // Every N minutes: */N * * * *
        Matcher everyMinM = EVERY_N.matcher(minute);
        if (everyMinM.matches() && Strings.CS.equals("*", hour) && Strings.CS.equals("*", dayOfMonth)
                && Strings.CS.equals("*", month) && Strings.CS.equals("*", dayOfWeek)) {
            int n = Integer.parseInt(everyMinM.group(1));
            return n == 1 ? "Every minute" : "Every " + n + " minutes";
        }

        // Every hour: M * * * *  (M is plain integer)
        if (UNSIGNED_INTEGER.matcher(minute).matches() && Strings.CS.equals("*", hour) && Strings.CS.equals("*", dayOfMonth)
                && Strings.CS.equals("*", month) && Strings.CS.equals("*", dayOfWeek)) {
            int m = Integer.parseInt(minute);
            return m == 0 ? "Every hour" : "Every hour at :" + String.format("%02d", m);
        }

        // Every N hours: M */N * * *
        Matcher everyHrM = EVERY_N.matcher(hour);
        if (UNSIGNED_INTEGER.matcher(minute).matches() && everyHrM.matches() && Strings.CS.equals("*", dayOfMonth)
                && Strings.CS.equals("*", month) && Strings.CS.equals("*", dayOfWeek)) {
            int n  = Integer.parseInt(everyHrM.group(1));
            int m  = Integer.parseInt(minute);
            String suffix = m == 0 ? "" : " at :" + String.format("%02d", m);
            return n == 1 ? "Every hour" + suffix : "Every " + n + " hours" + suffix;
        }

        // Remaining cases need both minute and hour as plain integers
        if (!UNSIGNED_INTEGER.matcher(minute).matches()
                || !UNSIGNED_INTEGER.matcher(hour).matches()) return cron;
        int m = Integer.parseInt(minute);
        int h = Integer.parseInt(hour);
        String fmtTime = formatLocalTime(m, h);

        // Daily at specific time: M H * * *
        if (Strings.CS.equals("*", dayOfMonth) && Strings.CS.equals("*", month) && Strings.CS.equals("*", dayOfWeek)) {
            return "Every day at " + fmtTime;
        }

        // Specific day of week: M H * * D
        if (Strings.CS.equals("*", dayOfMonth) && Strings.CS.equals("*", month)
                && SINGLE_DIGIT.matcher(dayOfWeek).matches()) {
            int dayIdx = Integer.parseInt(dayOfWeek) % 7; // normalize 7→0 (Sunday alias)
// dayOfWeek is a single digit (\\d → 0-9), so % 7 yields 0-6.
            return "Every " + DAY_NAMES[dayIdx] + " at " + fmtTime;
        }

        // Weekdays: M H * * 1-5
        if (Strings.CS.equals("*", dayOfMonth) && Strings.CS.equals("*", month) && Strings.CS.equals("1-5", dayOfWeek)) {
            return "Weekdays at " + fmtTime;
        }

        return cron;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Parse 5-field cron expr into [minute[], hour[], dom[], month[], dow[]].
     */
    private static int[][] parse(String expr) {
        if (expr == null) return null;
        String[] parts = WHITESPACE.split(expr.trim());
        if (parts.length != 5) return null;
        int[][] result = new int[5][];
        for (int i = 0; i < 5; i++) {
            int[] expanded = expandField(parts[i], FIELD_RANGES[i][0], FIELD_RANGES[i][1]);
            if (expanded == null) return null;
            result[i] = expanded;
        }
        return result;
    }

    /**
     * Expand a single cron field into a sorted int array.
     */
    private static int[] expandField(String field, int min, int max) {
        Set<Integer> out = new TreeSet<>();
        for (String part : field.split(",")) {
            // * or */N
            Matcher stepM = WILDCARD_STEP.matcher(part);
            if (stepM.matches()) {
                int step = stepM.group(1) != null ? Integer.parseInt(stepM.group(1)) : 1;
                if (step < 1) return null;
                for (int i = min; i <= max; i += step) out.add(i);
                continue;
            }
            // N-M or N-M/S
            Matcher rangeM = RANGE_STEP.matcher(part);
            if (rangeM.matches()) {
                int lo   = Integer.parseInt(rangeM.group(1));
                int hi   = Integer.parseInt(rangeM.group(2));
                int step = rangeM.group(3) != null ? Integer.parseInt(rangeM.group(3)) : 1;
                boolean isDow = min == 0 && max == 6;
                int effMax = isDow ? 7 : max;
                if (lo > hi || step < 1 || lo < min || hi > effMax) return null;
                for (int i = lo; i <= hi; i += step) {
                    out.add(isDow && i == 7 ? 0 : i);
                }
                continue;
            }
            // plain N
            if (UNSIGNED_INTEGER.matcher(part).matches()) {
                int n = Integer.parseInt(part);
                boolean isDow = min == 0 && max == 6;
                if (isDow && n == 7) n = 0;
                if (n < min || n > max) return null;
                out.add(n);
                continue;
            }
            return null; // unknown syntax
        }
        if (out.isEmpty()) return null;
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Compute next LocalDateTime strictly after {@code from} matching the parsed fields.
     */
    private static LocalDateTime computeNext(int[][] fields, LocalDateTime from) {
        Set<Integer> minuteSet   = toSet(fields[0]);
        Set<Integer> hourSet     = toSet(fields[1]);
        Set<Integer> domSet      = toSet(fields[2]);
        Set<Integer> monthSet    = toSet(fields[3]);
        Set<Integer> dowSet      = toSet(fields[4]);

        boolean domWild = fields[2].length == 31;
        boolean dowWild = fields[4].length == 7;

        // Round up to next whole minute (strictly after from)
        LocalDateTime t = from.withSecond(0).withNano(0).plusMinutes(1);

        int maxIter = 366 * 24 * 60;
        for (int i = 0; i < maxIter; i++) {
            int month = t.getMonthValue();
            if (!monthSet.contains(month)) {
                t = t.withDayOfMonth(1).withHour(0).withMinute(0)
                     .plusMonths(1);
                continue;
            }

            int dom = t.getDayOfMonth();
            int dow = t.getDayOfWeek().getValue() % 7; // Java: 1=Mon..7=Sun → normalize: 0=Sun
            boolean dayMatches;
            if (domWild && dowWild) {
                dayMatches = true;
            } else if (domWild) {
                dayMatches = dowSet.contains(dow);
            } else if (dowWild) {
                dayMatches = domSet.contains(dom);
            } else {
                dayMatches = domSet.contains(dom) || dowSet.contains(dow);
            }

            if (!dayMatches) {
                t = t.plusDays(1).withHour(0).withMinute(0);
                continue;
            }

            if (!hourSet.contains(t.getHour())) {
                t = t.plusHours(1).withMinute(0);
                continue;
            }

            if (!minuteSet.contains(t.getMinute())) {
                t = t.plusMinutes(1);
                continue;
            }

            return t;
        }
        return null;
    }

    /**
     * Format local time in 12-hour "h:mm AM/PM" style.
     */
    private static String formatLocalTime(int minute, int hour) {
        LocalTime lt = LocalTime.of(hour, minute);
        // "h:mm a" → e.g. "9:00 AM", "12:30 PM"
        return lt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US));
    }

    private static Set<Integer> toSet(int[] arr) {
        Set<Integer> s = new HashSet<>();
        for (int v : arr) s.add(v);
        return s;
    }
}
