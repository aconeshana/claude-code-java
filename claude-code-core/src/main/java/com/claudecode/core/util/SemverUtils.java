package com.claudecode.core.util;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Loose semantic-version comparison.
 *
 * <ul>
 *   <li>{@code gt}, {@code gte}, {@code lt},
 *       {@code lte}, {@code order}, and the comparator/caret/tilde/wildcard
 *       forms consumed by {@code satisfies}.</li>
 * </ul>
 */
public final class SemverUtils {
    private static final Pattern HYPHEN_RANGE = Pattern.compile("^(.+?)\\s+-\\s+(.+)$");
    private static final Pattern VERSION_PREFIX = Pattern.compile("^[=vV]\\s*");
    private static final Pattern INTEGER_PREFIX = Pattern.compile("^(\\d+)");
    private static final Pattern NUMERIC_IDENTIFIER = Pattern.compile("\\d+");

    private SemverUtils() {}

    public static boolean gt(String a, String b) { return order(a, b) > 0; }
    public static boolean gte(String a, String b) { return order(a, b) >= 0; }
    public static boolean lt(String a, String b) { return order(a, b) < 0; }
    public static boolean lte(String a, String b) { return order(a, b) <= 0; }

    public static int order(String a, String b) {
        return parse(a).compareTo(parse(b));
    }

    public static boolean satisfies(String version, String range) {
        Version candidate = parse(version);
        if (StringUtils.isBlank(range) || Strings.CS.equals("*", range.trim())) return true;
        for (String alternative : range.split("\\s*\\|\\|\\s*")) {
            if (satisfiesAll(candidate, alternative.trim())) return true;
        }
        return false;
    }

    private static boolean satisfiesAll(Version value, String range) {
        var hyphen = HYPHEN_RANGE.matcher(range);
        if (hyphen.matches()) {
            return value.compareTo(parse(hyphen.group(1))) >= 0
                && value.compareTo(parse(hyphen.group(2))) <= 0;
        }
        for (String token : range.split("[ ,]+")) {
            if (StringUtils.isBlank(token)) continue;
            if (!satisfiesToken(value, token)) return false;
        }
        return true;
    }

    private static boolean satisfiesToken(Version value, String token) {
        if (Strings.CS.equals(token, "*") || Strings.CI.equals(token, "x")) return true;
        if (Strings.CS.startsWith(token, "^")) {
            Version low = parse(token.substring(1));
            Version high = low.major > 0 ? new Version(low.major + 1, 0, 0, List.of())
                : low.minor > 0 ? new Version(0, low.minor + 1, 0, List.of())
                : new Version(0, 0, low.patch + 1, List.of());
            return value.compareTo(low) >= 0 && value.compareTo(high) < 0;
        }
        if (Strings.CS.startsWith(token, "~")) {
            Version low = parse(token.substring(1));
            Version high = new Version(low.major, low.minor + 1, 0, List.of());
            return value.compareTo(low) >= 0 && value.compareTo(high) < 0;
        }
        String operator = Strings.CS.startsWith(token, ">=") || Strings.CS.startsWith(token, "<=")
            ? token.substring(0, 2) : token.substring(0, 1);
        if (List.of(">=", "<=", ">", "<", "=").contains(operator)) {
            int compared = value.compareTo(parse(token.substring(operator.length())));
            return switch (operator) {
                case ">=" -> compared >= 0; case "<=" -> compared <= 0;
                case ">" -> compared > 0; case "<" -> compared < 0; default -> compared == 0;
            };
        }
        String normalized = token.toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(normalized, "x") || Strings.CS.contains(normalized, "*")) {
            String[] parts = normalized.replace('*', 'x').split("\\.");
            Version actual = value;
            if (parts.length > 0 && !Strings.CS.equals("x", parts[0])
                    && actual.major != integer(parts[0])) return false;
            if (parts.length > 1 && !Strings.CS.equals("x", parts[1])
                    && actual.minor != integer(parts[1])) return false;
            return parts.length <= 2 || Strings.CS.equals("x", parts[2]) || actual.patch == integer(parts[2]);
        }
        return value.compareTo(parse(token)) == 0;
    }

    private static Version parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("version must not be null");
        String value = VERSION_PREFIX.matcher(raw.trim()).replaceFirst("");
        int plus = value.indexOf('+');
        if (plus >= 0) value = value.substring(0, plus);
        String[] mainPre = value.split("-", 2);
        String[] nums = mainPre[0].split("\\.");
        int major = nums.length > 0 ? integerPrefix(nums[0]) : 0;
        int minor = nums.length > 1 ? integerPrefix(nums[1]) : 0;
        int patch = nums.length > 2 ? integerPrefix(nums[2]) : 0;
        List<String> pre = mainPre.length == 1 ? List.of()
            : List.of(mainPre[1].split("\\."));
        return new Version(major, minor, patch, pre);
    }

    private static int integerPrefix(String value) {
        var matcher = INTEGER_PREFIX.matcher(value.trim());
        if (!matcher.find()) throw new IllegalArgumentException("Invalid semantic version: " + value);
        return integer(matcher.group(1));
    }

    private static int integer(String value) { return Integer.parseInt(value); }

    private record Version(int major, int minor, int patch, List<String> prerelease)
            implements Comparable<Version> {
        @Override public int compareTo(Version other) {
            int c = Integer.compare(major, other.major);
            if (c == 0) c = Integer.compare(minor, other.minor);
            if (c == 0) c = Integer.compare(patch, other.patch);
            if (c != 0) return Integer.signum(c);
            if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
                return prerelease.isEmpty() == other.prerelease.isEmpty() ? 0
                    : prerelease.isEmpty() ? 1 : -1;
            }
            int size = Math.max(prerelease.size(), other.prerelease.size());
            for (int i = 0; i < size; i++) {
                if (i >= prerelease.size()) return -1;
                if (i >= other.prerelease.size()) return 1;
                String a = prerelease.get(i), b = other.prerelease.get(i);
                boolean an = NUMERIC_IDENTIFIER.matcher(a).matches();
                boolean bn = NUMERIC_IDENTIFIER.matcher(b).matches();
                c = an && bn ? Integer.compare(integer(a), integer(b))
                    : an ? -1 : bn ? 1 : a.compareTo(b);
                if (c != 0) return Integer.signum(c);
            }
            return 0;
        }
    }
}
