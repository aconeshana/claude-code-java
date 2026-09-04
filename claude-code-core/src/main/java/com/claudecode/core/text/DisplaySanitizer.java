package com.claudecode.core.text;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Model- and tool-supplied text that is about to be painted into the terminal, scrubbed of the
 * characters that let a string lie about its own shape: bidi overrides, default-ignorables,
 * lone surrogates, C0/C1 controls, and forged truncation markers.
 *
 * <p>This layer has no counterpart in the reverse-engineered 2.1.197 tree — it was introduced in
 * 2.1.236, and the authoritative formulas below were extracted verbatim from the
 * {@code 2.1.236} bundle. Symbol names in the coverage list are that bundle's minified names.
 *
 * <ul>
 *   <li>Covers: {@code To} — code-unit truncation that never splits a surrogate pair. See
 *       {@link #truncateCodeUnits(String, int)}.</li>
 *   <li>Covers: {@code Xyt} / {@code $cr} (strict) and {@code FT} (permissive) — the two scrub
 *       projections, each {@code Bg(JCf(text, predicate))}. See {@link #scrub(String)} and
 *       {@link #scrubPermissive(String)}.</li>
 *   <li>Covers: {@code JCf}, {@code FEi}, {@code Tfv}, {@code vfv} — the per-code-point unsafe
 *       predicates and the permissive whitelist that keeps emoji joiners and variation
 *       selectors intact.</li>
 *   <li>Covers: {@code Bg} / {@code cWd}, {@code s2b}, {@code iWd}, {@code sWd}, {@code a2b} —
 *       variation-selector removal, control/bidi/lone-surrogate replacement, and the
 *       {@code nYo} tab-stop expansion that follows. {@code nYo} delegates to
 *       {@link FormatUtils#expandTabs(String)}.</li>
 *   <li>Covers: {@code Yi} — grapheme-aware truncation to a terminal column budget.</li>
 *   <li>Covers: {@code qVa} — JSON-quoting a value whose collapsed form differs from its
 *       scrubbed form, or that mimics quoting/truncation.</li>
 *   <li>Covers: {@code ZCf} and {@code XCf} — the label and header projections.</li>
 *   <li>Covers: {@code Cfv} — the 2000-code-unit clamp with tab flattening and an appended
 *       ellipsis. See {@link #clampText(String)}.</li>
 *   <li>Covers: {@code i9} — the "needs a multi-line slot" test. See
 *       {@link #needsGutter(String)}.</li>
 *   <li>Covers: {@code u6e} — whether a string still renders something after scrubbing.</li>
 *   <li>Covers: {@code fA} — newline flattening to {@code U+FFFD}.</li>
 *   <li>Covers: {@code _le} / {@code W9r} — NFC-keyed display-label de-duplication with escaped
 *       fallbacks and {@code (#N)} suffixes.</li>
 *   <li>Covers: {@code xe} — {@code JSON.stringify} of a string, per ECMA-262 QuoteJSONString
 *       (well-formed, so lone surrogates escape). See {@link #jsonQuote(String)}.</li>
 * </ul>
 *
 * <p>Not covered: the bundle's {@code m2b} per-line grapheme clamp (thresholds {@code Nua=4096}
 * graphemes per line and above), which {@code Bg} applies after {@code nYo}. Every caller in this
 * port feeds {@code Bg} a string already clamped to at most {@value #TEXT_LIMIT} code units, so
 * the clamp cannot fire; it is deliberately not ported rather than approximated. Its companion
 * {@code Cpt} — the {@code /… \[\+\d+ graphemes\]/} forged-marker probe — is likewise absent: in
 * the bundle it is consulted only by the generic tool-input renderer {@code iM} and by
 * {@code eRe}, never by the question projection {@code w2g}.
 *
 * <p>All methods are static and null-tolerant; this class is not instantiated.
 */
public final class DisplaySanitizer {

    /** {@code gle} — code-unit budget for option labels and question headers. */
    public static final int LABEL_LIMIT = 256;

    /** {@code NWn} / {@code nFg} — code-unit budget for free-form text and previews. */
    public static final int TEXT_LIMIT = 2000;

    /** {@code $Ei} — terminal-column budget for a rendered header. */
    public static final int HEADER_WIDTH_LIMIT = 48;

    /** {@code i9}'s column threshold above which text claims a multi-line slot. */
    public static final int GUTTER_WIDTH_THRESHOLD = 80;

    /** The replacement emitted for every character judged unsafe to render. */
    public static final String REPLACEMENT = "�";

    private static final String ELLIPSIS = "…";

    /**
     * ECMAScript's {@code \s} class, which is wider than Java's: it also spans NBSP, the Unicode
     * space separators, the line/paragraph separators, and the BOM.
     */
    private static final Pattern JS_WHITESPACE = Pattern.compile(
        "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+");

    /** {@code fA}'s character class. */
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\n\\r\\u2028\\u2029]");

    private DisplaySanitizer() {}

    // ── Truncation ──────────────────────────────────────────────────────────

    /**
     * {@code To} — clamps to {@code maxCodeUnits} UTF-16 code units, dropping a trailing high
     * surrogate rather than splitting the pair. Any resulting lone low surrogate is left for
     * {@link #scrub(String)} to replace.
     */
    public static String truncateCodeUnits(String value, int maxCodeUnits) {
        if (value == null) return "";
        if (maxCodeUnits <= 0) return "";
        if (value.length() <= maxCodeUnits) return value;
        String head = value.substring(0, maxCodeUnits);
        return Character.isHighSurrogate(head.charAt(maxCodeUnits - 1))
            ? head.substring(0, head.length() - 1)
            : head;
    }

    /**
     * {@code Yi} — clamps to {@code maxWidth} terminal columns on grapheme boundaries, appending
     * an ellipsis. The ellipsis is budgeted for, so the result never exceeds {@code maxWidth}.
     */
    public static String truncateToWidth(String value, int maxWidth) {
        if (value == null) return "";
        if (FormatUtils.displayWidth(value) <= maxWidth) return value;
        if (maxWidth <= 1) return ELLIPSIS;
        StringBuilder kept = new StringBuilder();
        int width = 0;
        for (String grapheme : graphemes(value)) {
            int graphemeWidth = FormatUtils.displayWidth(grapheme);
            if (width + graphemeWidth > maxWidth - 1) break;
            kept.append(grapheme);
            width += graphemeWidth;
        }
        return kept.append(ELLIPSIS).toString();
    }

    /**
     * {@code Cfv} — clamps to {@value #TEXT_LIMIT} code units, flattens tabs to single spaces, and
     * marks a clamped result with a trailing ellipsis.
     */
    public static String clampText(String value) {
        if (value == null) return "";
        String clamped = truncateCodeUnits(value, TEXT_LIMIT);
        String flattened = clamped.replace('\t', ' ');
        return clamped.equals(value) ? flattened : flattened + ELLIPSIS;
    }

    // ── Scrubbing ───────────────────────────────────────────────────────────

    /**
     * {@code Xyt} — the strict projection: every default-ignorable, format character, line or
     * paragraph separator, blank Braille pattern, and lone surrogate becomes {@value #REPLACEMENT}.
     */
    public static String scrub(String value) {
        return stripControlsAndExpandTabs(replaceUnsafe(value, false));
    }

    /**
     * {@code FT} — the permissive projection, which spares the joiners and variation selectors
     * that legitimate emoji sequences need ({@code vfv}). Note that
     * {@link #stripControlsAndExpandTabs} still drops VS15/VS16 and still replaces the Arabic
     * letter mark, so the whitelist only widens what survives the first pass.
     */
    public static String scrubPermissive(String value) {
        return stripControlsAndExpandTabs(replaceUnsafe(value, true));
    }

    /** {@code JCf} with either {@code FEi} (strict) or {@code Tfv} (permissive). */
    private static String replaceUnsafe(String value, boolean permissive) {
        if (StringUtils.isEmpty(value)) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            boolean unsafe = isUnsafe(codePoint)
                && !(permissive && isEmojiControlWhitelisted(codePoint));
            if (unsafe) out.append(REPLACEMENT);
            else out.appendCodePoint(codePoint);
        }
        return out.toString();
    }

    /** {@code FEi}. */
    private static boolean isUnsafe(int codePoint) {
        return UCharacter.hasBinaryProperty(codePoint, UProperty.DEFAULT_IGNORABLE_CODE_POINT)
            || codePoint == 0x2028
            || codePoint == 0x2029
            || codePoint == 0x2800
            || isLoneSurrogate(codePoint)
            || Character.getType(codePoint) == Character.FORMAT;
    }

    /** {@code vfv} — joiners, direction marks, variation selectors, and the blank Braille cell. */
    private static boolean isEmojiControlWhitelisted(int codePoint) {
        return (codePoint >= 0x200C && codePoint <= 0x200F)
            || codePoint == 0x061C
            || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
            || (codePoint >= 0x180B && codePoint <= 0x180F)
            || codePoint == 0x2800;
    }

    /**
     * {@code Bg} / {@code cWd} — drops the emoji presentation selectors outright, replaces
     * controls, bidi embedding marks, and lone surrogates, then expands tabs to 8-column stops.
     */
    private static String stripControlsAndExpandTabs(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isVariationSelector(codePoint)) continue;
            if (isControl(codePoint) || isBidiControl(codePoint) || isLoneSurrogate(codePoint)) {
                out.append(REPLACEMENT);
            } else {
                out.appendCodePoint(codePoint);
            }
        }
        return FormatUtils.expandTabs(out.toString());
    }

    /** {@code s2b} — VS15 and VS16, which only shift emoji presentation. */
    private static boolean isVariationSelector(int codePoint) {
        return codePoint == 0xFE0E || codePoint == 0xFE0F;
    }

    /** {@code iWd} — C0 and C1 controls, sparing tab and newline. */
    private static boolean isControl(int codePoint) {
        if (codePoint == '\t' || codePoint == '\n') return false;
        return codePoint < 0x20 || (codePoint >= 0x7F && codePoint <= 0x9F);
    }

    /** {@code sWd} — the Arabic letter mark plus the bidi embedding, override, and isolate marks. */
    private static boolean isBidiControl(int codePoint) {
        return codePoint == 0x061C
            || (codePoint >= 0x202A && codePoint <= 0x202E)
            || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    /** {@code a2b}. */
    private static boolean isLoneSurrogate(int codePoint) {
        return codePoint >= 0xD800 && codePoint <= 0xDFFF;
    }

    // ── Projections ─────────────────────────────────────────────────────────

    /** Collapses every run of ECMAScript whitespace to one space, then trims. */
    public static String collapseWhitespace(String value) {
        if (StringUtils.isEmpty(value)) return "";
        return JS_WHITESPACE.matcher(value).replaceAll(" ").strip();
    }

    /**
     * {@code qVa} — returns {@code collapsed} only when it is indistinguishable from
     * {@code scrubbed} and claims neither quoting nor truncation; otherwise returns the scrubbed
     * text JSON-quoted, so the reader can see exactly what was there.
     */
    public static String quoteIfSuspicious(String collapsed, String scrubbed, boolean overLimit) {
        boolean mimicsMarkup = Strings.CS.startsWith(collapsed, "\"")
            || Strings.CS.endsWith(collapsed, "\"")
            || Strings.CS.endsWith(collapsed, ELLIPSIS);
        return collapsed.equals(scrubbed) && !mimicsMarkup && !overLimit
            ? collapsed
            : jsonQuote(scrubbed);
    }

    /** {@code ZCf} — the label projection: clamp, scrub, collapse, then quote when suspicious. */
    public static String sanitizeLabel(String value) {
        if (value == null) return "";
        String scrubbed = scrub(truncateCodeUnits(value, LABEL_LIMIT));
        return quoteIfSuspicious(collapseWhitespace(scrubbed), scrubbed, value.length() > LABEL_LIMIT);
    }

    /** {@code XCf} — {@link #sanitizeLabel} clamped to {@value #HEADER_WIDTH_LIMIT} columns. */
    public static String sanitizeHeader(String value) {
        return truncateToWidth(sanitizeLabel(value), HEADER_WIDTH_LIMIT);
    }

    /** {@code fA} — newlines and separators become {@value #REPLACEMENT} so text stays on one row. */
    public static String flattenNewlines(String value) {
        if (value == null) return "";
        return LINE_BREAKS.matcher(value).replaceAll(REPLACEMENT);
    }

    /** {@code i9} — whether the text needs a multi-line slot rather than a single row. */
    public static boolean needsGutter(String value) {
        if (value == null) return false;
        return value.indexOf('\n') >= 0 || FormatUtils.displayWidth(value) > GUTTER_WIDTH_THRESHOLD;
    }

    /** {@code u6e} — whether anything visible survives scrubbing, replacement removal, and trimming. */
    public static boolean isVisiblyNonBlank(String value) {
        if (value == null) return false;
        String remaining = collapseWhitespace(scrub(value).replace(REPLACEMENT, ""));
        return !remaining.isEmpty() && FormatUtils.displayWidth(remaining) > 0;
    }

    // ── De-duplication ──────────────────────────────────────────────────────

    /** {@code _le} with its default {@code ZCf} key function. */
    public static List<String> dedupeDisplayLabels(List<String> values) {
        return dedupeDisplayLabels(values, DisplaySanitizer::sanitizeLabel);
    }

    /**
     * {@code _le} / {@code W9r} — projects each value through {@code keyFn}, and where two values
     * collide once NFC-normalised, replaces the colliding entries with an escaped form in which
     * every non-ASCII code unit is spelled {@code \\uXXXX}. Identical escaped forms then take a
     * {@code (#N)} suffix, so no two rendered labels are ever the same string.
     */
    public static List<String> dedupeDisplayLabels(List<String> values, UnaryOperator<String> keyFn) {
        if (values == null || values.isEmpty()) return List.of();
        Map<String, Set<String>> byKey = new LinkedHashMap<>();
        for (String value : values) {
            byKey.computeIfAbsent(normalizedKey(value, keyFn), _ -> new LinkedHashSet<>()).add(value);
        }
        Map<String, Map<String, Integer>> ordinals = new LinkedHashMap<>();
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            if (byKey.get(normalizedKey(value, keyFn)).size() <= 1) {
                result.add(keyFn.apply(value));
                continue;
            }
            String escaped = escapeNonAscii(value);
            Map<String, Integer> seen = ordinals.computeIfAbsent(escaped, _ -> new LinkedHashMap<>());
            Integer assigned = seen.get(value);
            int ordinal = assigned != null ? assigned : seen.size() + 1;
            seen.put(value, ordinal);
            result.add(ordinal > 1 ? escaped + " (#" + ordinal + ")" : escaped);
        }
        return List.copyOf(result);
    }

    private static String normalizedKey(String value, UnaryOperator<String> keyFn) {
        return Normalizer.normalize(keyFn.apply(value), Normalizer.Form.NFC);
    }

    /** {@code W9r}'s inner {@code o} — JSON-quote, then spell out every non-ASCII code unit. */
    private static String escapeNonAscii(String value) {
        String quoted = jsonQuote(truncateCodeUnits(value, TEXT_LIMIT));
        StringBuilder out = new StringBuilder(quoted.length());
        for (int index = 0; index < quoted.length(); index++) {
            char unit = quoted.charAt(index);
            if (unit >= 0x7F) out.append(String.format(Locale.ROOT, "\\u%04x", (int) unit));
            else out.append(unit);
        }
        if (value.length() > TEXT_LIMIT) out.append(ELLIPSIS);
        return out.toString();
    }

    // ── JSON quoting ────────────────────────────────────────────────────────

    /**
     * {@code xe} — {@code JSON.stringify} of a string: the ECMA-262 QuoteJSONString algorithm,
     * including the well-formed escaping of lone surrogates.
     */
    public static String jsonQuote(String value) {
        if (value == null) return "\"\"";
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            switch (unit) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> appendQuoted(out, value, index, unit);
            }
        }
        return out.append('"').toString();
    }

    private static void appendQuoted(StringBuilder out, String value, int index, char unit) {
        boolean unpaired = Character.isHighSurrogate(unit)
            ? index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))
            : Character.isLowSurrogate(unit)
                && (index == 0 || !Character.isHighSurrogate(value.charAt(index - 1)));
        if (unit < 0x20 || unpaired) out.append(String.format(Locale.ROOT, "\\u%04x", (int) unit));
        else out.append(unit);
    }

    // ── Grapheme iteration ──────────────────────────────────────────────────

    private static List<String> graphemes(String value) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(value);
        List<String> result = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            result.add(value.substring(start, end));
        }
        return result;
    }
}
