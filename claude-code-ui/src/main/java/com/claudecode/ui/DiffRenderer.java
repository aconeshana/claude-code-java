package com.claudecode.ui;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.constants.AnsiStyle;
import com.claudecode.core.diff.StructuredPatchHunk;

import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.theme.RgbColor;
import com.claudecode.ui.lanterna.theme.Theme;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.syntax.ScopeColorMap;
import com.claudecode.ui.syntax.TmTokenizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders unified diff output.
 */
public final class DiffRenderer {

    private DiffRenderer() {}

    /**
     * Render a unified diff string with ANSI colors.
     * Expects standard unified diff format lines.
     */
    public static String renderUnifiedDiff(String diff) {
        if (StringUtils.isEmpty(diff)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : diff.split("\n", -1)) {
            sb.append(renderDiffLine(line));
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Render a single diff line with appropriate color.
     */
    public static String renderDiffLine(String line) {
        if (StringUtils.isEmpty(line)) {
            return "";
        }
        if (Strings.CS.startsWith(line, "+++") || Strings.CS.startsWith(line, "---")) {
            return Ansi.styled(line, AnsiStyle.BOLD);
        }
        Theme theme = LanternaTheme.activeTheme();
        if (Strings.CS.startsWith(line, "@@")) {

            RgbColor c = theme.subtle();
            return Ansi.coloredRgb(line, c.r(), c.g(), c.b());
        }
        if (Strings.CS.startsWith(line, "+")) {
            RgbColor c = theme.diffAdded();
            return Ansi.coloredRgb(line, c.r(), c.g(), c.b());
        }
        if (Strings.CS.startsWith(line, "-")) {
            RgbColor c = theme.diffRemoved();
            return Ansi.coloredRgb(line, c.r(), c.g(), c.b());
        }

        return Ansi.styled(line, AnsiStyle.DIM);
    }

    /**
     * Generate a simple unified diff between two texts.
     * Uses a basic line-by-line comparison with context lines.
     *
     * @param oldText  the original text
     * @param newText  the modified text
     * @param fileName the file name for the diff header
     * @param contextLines number of context lines around changes
     * @return unified diff string
     */
    public static String generateDiff(String oldText, String newText, String fileName, int contextLines) {
        String[] oldLines = oldText.split("\n", -1);
        String[] newLines = newText.split("\n", -1);

        // Simple LCS-based diff
        List<DiffLine> diffLines = computeDiff(oldLines, newLines);

        // Format as unified diff
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(fileName).append("\n");
        sb.append("+++ b/").append(fileName).append("\n");

        // Group changes into hunks with context
        List<Hunk> hunks = groupIntoHunks(diffLines, contextLines);
        for (Hunk hunk : hunks) {
            sb.append(String.format("@@ -%d,%d +%d,%d @@%n",
                    hunk.oldStart, hunk.oldCount, hunk.newStart, hunk.newCount));
            for (DiffLine dl : hunk.lines) {
                switch (dl.type) {
                    case CONTEXT -> sb.append(" ").append(dl.content).append("\n");
                    case ADDED -> sb.append("+").append(dl.content).append("\n");
                    case REMOVED -> sb.append("-").append(dl.content).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * Generate a diff and render it with ANSI colors.
     */
    public static String generateAndRenderDiff(String oldText, String newText, String fileName) {
        String diff = generateDiff(oldText, newText, fileName, 3);
        return renderUnifiedDiff(diff);
    }

    // ── Structured engine (consumed by DiffDialog) ──────────────────────────

    /** Color kind for a rendered diff segment. */
    public enum SegKind { COMMON, ADDED, REMOVED, HUNK }

    /** One colored run within a diff line. */
    public record Segment(String text, SegKind kind, RgbColor foreground) {
        public Segment(String text, SegKind kind) {
            this(text, kind, null);
        }
    }


    public record DiffLineView(Integer lineNo, char marker, List<Segment> segments) {}

    /**
     * Above this ratio of changed characters to total length, {@link #renderHunk} stops word-level
     * diffing and renders the whole line as added/removed.
     */
    private static final double WORD_DIFF_CHANGE_THRESHOLD = 0.4;

    /**
     * Render a structured hunk into word-level, gutter-aware line views.
     */
    public static List<DiffLineView> renderHunk(StructuredPatchHunk hunk) {
        return renderHunk(hunk, null);
    }

    /** Render a hunk and optionally add TextMate syntax foreground colors. */
    public static List<DiffLineView> renderHunk(StructuredPatchHunk hunk, String language) {
        List<String> lines = hunk.lines();

        // Step 1: transform to typed line objects (prefix stripped).
        List<LineObj> objs = transformLines(lines);

        // Step 2: group adjacent remove-blocks -> add-blocks and pair by index.
        List<LineObj> grouped = processAdjacent(objs);

// Step 3: assign a single gutter line number (matches numberDiffLines).
        numberLines(grouped, hunk.oldStart());


        List<DiffLineView> out = new ArrayList<>();
        out.add(new DiffLineView(null, '@',
            List.of(new Segment(String.format("@@ -%d,%d +%d,%d @@",
                hunk.oldStart(), hunk.oldLines(), hunk.newStart(), hunk.newLines()),
                SegKind.HUNK))));

        for (LineObj o : grouped) {
            if (Strings.CS.equals("hunk", o.type)) continue; // header already synthesized
            out.add(new DiffLineView(o.lineNo, o.marker, renderLine(o)));
        }
        return decorateWithSyntax(out, language);
    }

    private static List<DiffLineView> decorateWithSyntax(
            List<DiffLineView> views, String language) {
        if (UiSettings.readSyntaxHighlightingDisabled()
                || !TmTokenizer.isSupported(language) || views.size() <= 1) {
            return views;
        }
        List<String> sourceLines = new ArrayList<>(views.size() - 1);
        for (int i = 1; i < views.size(); i++) {
            sourceLines.add(joinText(views.get(i).segments()));
        }
        TmTokenizer.TokenizedCode tokenized =
            TmTokenizer.tokenize(String.join("\n", sourceLines), language);
        if (tokenized == null || tokenized.isEmpty()) return views;

        List<DiffLineView> decorated = new ArrayList<>(views.size());
        decorated.add(views.getFirst());
        String theme = LanternaTheme.activeThemeName();
        for (int i = 1; i < views.size(); i++) {
            DiffLineView view = views.get(i);
            String text = sourceLines.get(i - 1);
            List<TmTokenizer.TmToken> tokens = i - 1 < tokenized.lines().size()
                ? tokenized.lines().get(i - 1) : List.of();
            decorated.add(new DiffLineView(view.lineNo(), view.marker(),
                colorSegments(view.segments(), text, tokens, theme)));
        }
        return List.copyOf(decorated);
    }

    private static String joinText(List<Segment> segments) {
        StringBuilder text = new StringBuilder();
        segments.forEach(segment -> text.append(segment.text()));
        return text.toString();
    }

    private static List<Segment> colorSegments(
            List<Segment> segments, String line, List<TmTokenizer.TmToken> tokens,
            String theme) {
        if (line.isEmpty() || tokens.isEmpty()) return segments;
        List<Segment> out = new ArrayList<>();
        int lineOffset = 0;
        for (Segment segment : segments) {
            int segmentStart = lineOffset;
            int segmentEnd = segmentStart + segment.text().length();
            int cursor = segmentStart;
            while (cursor < segmentEnd) {
                TmTokenizer.TmToken token = tokenAt(tokens, cursor);
                int boundary;
                RgbColor foreground = null;
                if (token != null) {
                    boundary = Math.min(segmentEnd, Math.max(cursor + 1, token.end()));
                    String tokenText = line.substring(cursor, boundary);
                    foreground = ScopeColorMap.scopeColor(token.scopes(), tokenText, theme);
                } else {
                    boundary = Math.min(segmentEnd, nextTokenStart(tokens, cursor, segmentEnd));
                }
                appendColored(out,
                    line.substring(cursor, boundary), segment.kind(), foreground);
                cursor = boundary;
            }
            lineOffset = segmentEnd;
        }
        return List.copyOf(out);
    }

    private static TmTokenizer.TmToken tokenAt(
            List<TmTokenizer.TmToken> tokens, int offset) {
        for (TmTokenizer.TmToken token : tokens) {
            if (token.start() <= offset && offset < token.end()) return token;
        }
        return null;
    }

    private static int nextTokenStart(
            List<TmTokenizer.TmToken> tokens, int offset, int fallback) {
        int next = fallback;
        for (TmTokenizer.TmToken token : tokens) {
            if (token.start() > offset) next = Math.min(next, token.start());
        }
        return Math.max(offset + 1, next);
    }

    private static void appendColored(
            List<Segment> out, String text, SegKind kind, RgbColor foreground) {
        if (text.isEmpty()) return;
        if (!out.isEmpty()) {
            Segment previous = out.getLast();
            if (previous.kind() == kind
                    && Objects.equals(previous.foreground(), foreground)) {
                out.set(out.size() - 1,
                    new Segment(previous.text() + text, kind, foreground));
                return;
            }
        }
        out.add(new Segment(text, kind, foreground));
    }



    /** Mutable line object during rendering. */
    private static final class LineObj {
        final String content;   // prefix stripped
        final char marker;      // '+', '-', ' ', '@'
        final String type;      // "add" | "remove" | "nochange" | "hunk"
        int lineNo;
        boolean wordDiff;
        LineObj matched;
        List<Segment> segments; // resolved lazily by renderLine
        LineObj(String content, char marker, String type) {
            this.content = content;
            this.marker = marker;
            this.type = type;
        }
    }

    private static List<LineObj> transformLines(List<String> lines) {
        List<LineObj> objs = new ArrayList<>();
        for (String line : lines) {
            if (Strings.CS.startsWith(line, "+")) objs.add(new LineObj(line.substring(1), '+', "add"));
            else if (Strings.CS.startsWith(line, "-")) objs.add(new LineObj(line.substring(1), '-', "remove"));
            else if (Strings.CS.startsWith(line, "@@")) objs.add(new LineObj(line, '@', "hunk"));
            else objs.add(new LineObj(line.isEmpty() ? "" : line.substring(1), ' ', "nochange"));
        }
        return objs;
    }


    private static List<LineObj> processAdjacent(List<LineObj> objs) {
        List<LineObj> result = new ArrayList<>();
        int i = 0;
        while (i < objs.size()) {
            LineObj cur = objs.get(i);
            if (!Strings.CS.equals("remove", cur.type)) {
                result.add(cur);
                i++;
                continue;
            }
            List<LineObj> removes = new ArrayList<>();
            removes.add(cur);
            int j = i + 1;
            while (j < objs.size() && Strings.CS.equals("remove", objs.get(j).type)) {
                removes.add(objs.get(j));
                j++;
            }
            List<LineObj> adds = new ArrayList<>();
            while (j < objs.size() && Strings.CS.equals("add", objs.get(j).type)) {
                adds.add(objs.get(j));
                j++;
            }
            if (!removes.isEmpty() && !adds.isEmpty()) {
                int pair = Math.min(removes.size(), adds.size());
                for (int k = 0; k < pair; k++) {
                    removes.get(k).wordDiff = true;
                    adds.get(k).wordDiff = true;
                    removes.get(k).matched = adds.get(k);
                    adds.get(k).matched = removes.get(k);
                }
                result.addAll(removes);
                result.addAll(adds);
                i = j;
            } else {
                result.add(cur);
                i++;
            }
        }
        return result;
    }


    private static void numberLines(List<LineObj> objs, int start) {
        int i = start;
        int idx = 0;
        while (idx < objs.size()) {
            LineObj cur = objs.get(idx);
            switch (cur.type) {
                case "hunk" -> idx++;  // embedded header: no gutter no., no counter advance
                case "nochange", "add" -> {
                    cur.lineNo = i;
                    i++;
                    idx++;
                }
                case "remove" -> {
                    cur.lineNo = i;
                    int numRemoved = 0;
                    int j = idx + 1;
                    while (j < objs.size() && Strings.CS.equals("remove", objs.get(j).type)) {
                        i++;
                        objs.get(j).lineNo = i;
                        j++;
                        numRemoved++;
                    }
                    i -= numRemoved;
                    idx = j;
                }
                default -> idx++;
            }
        }
    }

    private static List<Segment> renderLine(LineObj o) {
        if (o.segments != null) return o.segments;
        if (o.wordDiff && o.matched != null) {
            resolveWordDiff(o, o.matched);
        }
        if (o.segments != null) return o.segments;
        SegKind kind = switch (o.type) {
            case "add" -> SegKind.ADDED;
            case "remove" -> SegKind.REMOVED;
            default -> SegKind.COMMON;
        };
        return List.of(new Segment(o.content, kind));
    }

/**
     * Compute the word-level diff for a paired remove/add, honoring the change threshold.
     */
    private static void resolveWordDiff(LineObj a, LineObj b) {
        String removedText = Strings.CS.equals(a.type, "remove") ? a.content : b.content;
        String addedText = Strings.CS.equals(a.type, "remove") ? b.content : a.content;
        List<DiffPart> parts = wordLevelDiff(removedText, addedText);
        int total = removedText.length() + addedText.length();
        int changed = 0;
        for (DiffPart p : parts) if (p.added() || p.removed()) changed += p.value().length();
        double ratio = total == 0 ? 0 : (double) changed / total;
        if (ratio > WORD_DIFF_CHANGE_THRESHOLD) {
            // Fall back to whole-line add/remove coloring.
            a.segments = List.of(new Segment(a.content,
                Strings.CS.equals(a.type, "remove") ? SegKind.REMOVED : SegKind.ADDED));
            b.segments = List.of(new Segment(b.content,
                Strings.CS.equals(b.type, "remove") ? SegKind.REMOVED : SegKind.ADDED));
            return;
        }
        a.segments = buildSegments(parts, false);
        b.segments = buildSegments(parts, true);
    }

    private static List<Segment> buildSegments(List<DiffPart> parts, boolean forAdded) {
        List<Segment> segs = new ArrayList<>();
        for (DiffPart p : parts) {
            if (forAdded) {
                if (p.added()) {
                    segs.add(new Segment(p.value(), SegKind.ADDED));
                    continue;
                }
            } else {
                if (p.removed()) {
                    segs.add(new Segment(p.value(), SegKind.REMOVED));
                    continue;
                }
            }
            // Common case for both sides: a token that is neither the unique
            // add nor the unique remove is shared (rendered COMMON).
            boolean isCommon = forAdded ? !p.removed() : !p.added();
            if (isCommon) {
                segs.add(new Segment(p.value(), SegKind.COMMON));
            }
        }
        return segs;
    }

/**
     * Word-level diff of two strings; returns aligned tokens.
     */
    private static List<DiffPart> wordLevelDiff(String a, String b) {
        List<String> ta = tokenize(a);
        List<String> tb = tokenize(b);
        int la = ta.size(), lb = tb.size();
        int[][] dp = new int[la + 1][lb + 1];
        for (int i = la - 1; i >= 0; i--) {
            for (int j = lb - 1; j >= 0; j--) {
                if (ta.get(i).equals(tb.get(j))) dp[i][j] = dp[i + 1][j + 1] + 1;
                else dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<DiffPart> parts = new ArrayList<>();
        int i = 0, j = 0;
        while (i < la && j < lb) {
            if (ta.get(i).equals(tb.get(j))) {
                parts.add(new DiffPart(false, false, ta.get(i)));
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                parts.add(new DiffPart(false, true, ta.get(i)));
                i++;
            } else {
                parts.add(new DiffPart(true, false, tb.get(j)));
                j++;
            }
        }
        while (i < la) { parts.add(new DiffPart(false, true, ta.get(i))); i++; }
        while (j < lb) { parts.add(new DiffPart(true, false, tb.get(j))); j++; }
        return parts;
    }

    private static List<String> tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        Matcher m = WORD_TOKEN.matcher(s);
        while (m.find()) tokens.add(m.group());
        return tokens;
    }

    private static final Pattern WORD_TOKEN = Pattern.compile("(\\s+|[^\\s]+)");

    /** One aligned token from a word-level diff: added XOR removed, else common. */
    private record DiffPart(boolean added, boolean removed, String value) {}

    // --- Internal diff computation ---

    enum DiffType { CONTEXT, ADDED, REMOVED }

    record DiffLine(DiffType type, String content, int oldLineNum, int newLineNum) {}

    record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<DiffLine> lines) {}

    static List<DiffLine> computeDiff(String[] oldLines, String[] newLines) {
        // Simple O(n*m) LCS for correctness
        int n = oldLines.length;
        int m = newLines.length;
        int[][] lcs = new int[n + 1][m + 1];

        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (oldLines[i].equals(newLines[j])) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        List<DiffLine> result = new ArrayList<>();
        int i = 0, j = 0;
        int oldNum = 1, newNum = 1;
        while (i < n || j < m) {
            if (i < n && j < m && oldLines[i].equals(newLines[j])) {
                result.add(new DiffLine(DiffType.CONTEXT, oldLines[i], oldNum++, newNum++));
                i++;
                j++;
            } else if (j < m && (i >= n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                result.add(new DiffLine(DiffType.ADDED, newLines[j], -1, newNum++));
                j++;
            } else {
                result.add(new DiffLine(DiffType.REMOVED, oldLines[i], oldNum++, -1));
                i++;
            }
        }
        return result;
    }

    static List<Hunk> groupIntoHunks(List<DiffLine> diffLines, int contextLines) {
        List<Hunk> hunks = new ArrayList<>();
        if (diffLines.isEmpty()) return hunks;

        // Find change indices
        List<Integer> changeIndices = new ArrayList<>();
        for (int i = 0; i < diffLines.size(); i++) {
            if (diffLines.get(i).type != DiffType.CONTEXT) {
                changeIndices.add(i);
            }
        }
        if (changeIndices.isEmpty()) return hunks;

        // Group changes that are close together
        int hunkStart = Math.max(0, changeIndices.getFirst() - contextLines);
        int hunkEnd = Math.min(diffLines.size() - 1, changeIndices.getFirst() + contextLines);

        List<int[]> ranges = new ArrayList<>();
        int rangeStart = hunkStart;
        int rangeEnd = hunkEnd;

        for (int ci = 1; ci < changeIndices.size(); ci++) {
            int newStart = Math.max(0, changeIndices.get(ci) - contextLines);
            int newEnd = Math.min(diffLines.size() - 1, changeIndices.get(ci) + contextLines);
            if (newStart > rangeEnd + 1) {
                ranges.add(new int[]{rangeStart, rangeEnd});
                rangeStart = newStart;
            }
            rangeEnd = newEnd;
        }
        ranges.add(new int[]{rangeStart, rangeEnd});

        for (int[] range : ranges) {
            List<DiffLine> hunkLines = diffLines.subList(range[0], Math.min(range[1] + 1, diffLines.size()));
            int oldStart = 1, newStart = 1, oldCount = 0, newCount = 0;
            boolean foundFirst = false;
            for (DiffLine dl : hunkLines) {
                if (!foundFirst) {
                    if (dl.oldLineNum > 0) oldStart = dl.oldLineNum;
                    if (dl.newLineNum > 0) newStart = dl.newLineNum;
                    foundFirst = true;
                }
                switch (dl.type) {
                    case CONTEXT -> { oldCount++; newCount++; }
                    case ADDED -> newCount++;
                    case REMOVED -> oldCount++;
                }
            }
            hunks.add(new Hunk(oldStart, oldCount, newStart, newCount, new ArrayList<>(hunkLines)));
        }
        return hunks;
    }
}
