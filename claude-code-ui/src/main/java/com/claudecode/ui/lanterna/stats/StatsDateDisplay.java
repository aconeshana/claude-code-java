package com.claudecode.ui.lanterna.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.apache.commons.lang3.StringUtils;

/**
 * Presentation-side parsing and heatmap date projection for neutral stats DTOs.
 */
public final class StatsDateDisplay {
    private static final DateTimeFormatter UTC_DATE =
        DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private StatsDateDisplay() {}

    public static String localMidnightUtcDate(LocalDate date, ZoneId zone) {
        return UTC_DATE.format(date.atStartOfDay(zone).toInstant());
    }

    public static String today() {
        return UTC_DATE.format(Instant.now());
    }

    public static Instant parseFlexible(String value) {
        if (StringUtils.isEmpty(value)) return null;
        try {
            return value.indexOf('T') >= 0
                ? OffsetDateTime.parse(value).toInstant()
                : LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception _) {
            return null;
        }
    }
}
