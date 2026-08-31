package com.claudecode.session.stats;

import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;


public final class StatsDates {

    private static final DateTimeFormatter UTC_DATE =
        DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private StatsDates() {}


    public static String toDateString(Instant instant) {
        return UTC_DATE.format(instant);
    }


    public static String today() {
        return toDateString(Instant.now());
    }


    public static String yesterday() {
        return toDateString(Instant.now().minus(1, ChronoUnit.DAYS));
    }


    public static boolean isBefore(String date1, String date2) {
        return date1.compareTo(date2) < 0;
    }


    public static String nextDay(String dateStr) {
        return LocalDate.parse(dateStr).plusDays(1).toString();
    }


    public static String localMidnightUtcDate(LocalDate localDate, ZoneId zone) {
        return toDateString(localDate.atStartOfDay(zone).toInstant());
    }

    public static Instant parseFlexible(String value) {
        if (StringUtils.isEmpty(value)) return null;
        try {
            if (value.indexOf('T') >= 0) {
                return OffsetDateTime.parse(value).toInstant();
            }
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception _) {
            return null;
        }
    }
}
