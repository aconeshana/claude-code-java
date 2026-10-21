package com.claudecode.tools.files;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.Strings;

/**
 * Matches an Edit {@code old_string} against file text that spells non-ASCII characters as
 * {@code \}{@code uXXXX} escapes (or the reverse), and carries the file's spelling over to
 * {@code new_string}.
 *
 * <ul>
 *   <li>2.1.236 bundle {@code L1t} (2.1.197 {@code cMe}) — the escape-decoding and
 *       escape-encoding fallbacks after the exact and curly-quote lookups.</li>
 *   <li>2.1.236 bundle {@code kOa} / {@code $Hp} — decode {@code \}{@code uXXXX} outside
 *       doubled backslashes; build the case-insensitive-hex pattern for a non-ASCII string.</li>
 *   <li>2.1.236 bundle {@code UHp} — rewrite {@code new_string} in the matched spelling, using
 *       the file's hex case when the replacement introduces characters the file never spelled.</li>
 *   <li>2.1.236 bundle {@code BHp} — the predicate that adds the "also tried swapping" note to
 *       the not-found error.</li>
 * </ul>
 */
final class EditEscapeMatching {

    static final String NOT_FOUND_NOTE =
        """

        (note: Edit also tried swapping \\uXXXX escapes and their characters; \
        neither form matched, so the mismatch is likely elsewhere in old_string. \
        Re-read the file and copy the exact surrounding text.)""";

    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u[0-9a-fA-F]{4}");
    private static final Pattern ESCAPE_OR_DOUBLED_BACKSLASH =
        Pattern.compile("(\\\\\\\\)|\\\\u([0-9a-fA-F]{4})");

    private EditEscapeMatching() {}

    /** True when the string holds a {@code \}{@code uXXXX} escape or a non-ASCII character. */
    static boolean mightInvolveEscapes(String text) {
        return UNICODE_ESCAPE.matcher(text).find() || hasNonAscii(text);
    }

    /**
     * Returns the substring of {@code content} that {@code search} refers to through the
     * escape/character swap, or {@code null} when neither direction matches.
     */
    static String findEscapeSwapped(String content, String search) {
        if (UNICODE_ESCAPE.matcher(search).find()) {
            String decoded = decodeEscapes(search);
            if (!decoded.equals(search) && Strings.CS.contains(content, decoded)) return decoded;
        }
        if (hasNonAscii(search)) {
            Matcher match = Pattern.compile(escapedForm(search)).matcher(content);
            if (match.find()) return match.group();
        }
        return null;
    }

    /** Rewrites {@code newString} in the spelling the file used for the matched text. */
    static String carryOverSpelling(String oldString, String actualOld, String newString) {
        if (oldString.equals(actualOld)) return newString;
        if (hasNonAscii(oldString)
                && Pattern.compile(escapedForm(oldString)).matcher(actualOld).matches()) {
            return reescape(oldString, actualOld, newString);
        }
        if (UNICODE_ESCAPE.matcher(oldString).find()
                && decodeEscapes(oldString).equals(actualOld)) {
            return decodeEscapes(newString);
        }
        return newString;
    }

    private static String reescape(String oldString, String actualOld, String newString) {
        Map<Character, String> fileHex = new HashMap<>();
        int upper = 0;
        int lower = 0;
        for (int i = 0, at = 0; i < oldString.length(); i++) {
            char ch = oldString.charAt(i);
            if (ch < 128) {
                at++;
                continue;
            }
            String hex = actualOld.substring(at + 2, at + 6);
            fileHex.put(ch, hex);
            for (char digit : hex.toCharArray()) {
                if (digit >= 'a' && digit <= 'f') lower++;
                else if (digit >= 'A' && digit <= 'F') upper++;
            }
            at += 6;
        }
        StringBuilder out = new StringBuilder(newString.length());
        for (char ch : newString.toCharArray()) {
            if (ch < 128) {
                out.append(ch);
                continue;
            }
            String hex = fileHex.get(ch);
            if (hex == null) {
                hex = String.format("%04x", (int) ch);
                if (upper > lower) hex = hex.toUpperCase(Locale.ROOT);
            }
            out.append("\\u").append(hex);
        }
        return out.toString();
    }

    private static String decodeEscapes(String text) {
        return ESCAPE_OR_DOUBLED_BACKSLASH.matcher(text).replaceAll(match ->
            Matcher.quoteReplacement(match.group(1) != null
                ? match.group()
                : String.valueOf((char) Integer.parseInt(match.group(2), 16))));
    }

    /** Pattern source matching {@code text} with every non-ASCII char written as {@code \}{@code uXXXX}. */
    private static String escapedForm(String text) {
        StringBuilder regex = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (ch < 128) {
                regex.append(Pattern.quote(String.valueOf(ch)));
                continue;
            }
            regex.append("\\\\u");
            for (char digit : String.format("%04x", (int) ch).toCharArray()) {
                if (digit >= 'a') {
                    regex.append('[').append(digit).append(Character.toUpperCase(digit)).append(']');
                } else {
                    regex.append(digit);
                }
            }
        }
        return regex.toString();
    }

    private static boolean hasNonAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 128) return true;
        }
        return false;
    }
}
