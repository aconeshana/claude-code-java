package com.claudecode.ui.lanterna.dialog.question;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.MarkdownRenderer;
import com.claudecode.ui.lanterna.components.AnsiToSegments;
import com.claudecode.ui.lanterna.components.TableBorders;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel.Segment;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * The bordered preview pane of the {@code AskUserQuestion} design card: an option's preview
 * rendered as markdown inside a box whose width adapts to its content.
 *
 * <p>Authority is the {@code 2.1.236} bundle. The weflow tree's
 * {@code components/permissions/AskUserQuestionPermissionRequest/PreviewBox.tsx} describes the same
 * component but is wrong in two places — it lacks the per-line hard wrap and the {@code max(4, …)}
 * floor on the box width — so the geometry below follows the binary.
 *
 * <ul>
 *   <li>Covers: {@code r$c} — box geometry, the {@code │ … │} rows, the cut bar, and the
 *       minimum-height padding. See {@link #render(String, int, int, int, int)}.</li>
 *   <li>Covers: {@code n$c} — the wrapper that injects the syntax highlighter. Here markdown and
 *       fenced-code highlighting both come from {@link MarkdownRenderer}, whose
 *       {@code visit(FencedCodeBlock)} is the counterpart of the bundle's {@code rce()}.</li>
 *   <li>Covers: {@code T2g} (minimum width 40) and the default {@code maxLines} of 20. See
 *       {@link #MIN_WIDTH} and {@link #DEFAULT_MAX_LINES}.</li>
 *   <li>Covers: {@code H$c}'s {@code XFg} loop — the width every box on a card shares. See
 *       {@link #sharedMinWidth(List)}.</li>
 * </ul>
 *
 * <p>Deviation: the bundle renders markdown unwrapped and then applies {@code wrap-ansi}. This port
 * asks {@link MarkdownRenderer} to lay out at the same width first — that width only reaches its
 * table and rule logic — and then applies the same wrap through {@link SegmentLines#wrap}. Trailing
 * blank lines from the renderer are dropped, since they would otherwise inflate the box by rows the
 * bundle's own markdown pass never produces.
 */
public final class PreviewBox {

    /** {@code T2g} — no preview box is ever narrower than this, so switching options cannot jitter. */
    public static final int MIN_WIDTH = 40;

    /** The bundle's default {@code maxLines} when the caller supplies none. */
    public static final int DEFAULT_MAX_LINES = 20;

    /** Shown when an option's preview is absent or renders to nothing. */
    public static final String NO_PREVIEW = "No preview available";

    /** Border plus the one column of padding on each side. */
    private static final int FRAME_WIDTH = 4;

    private PreviewBox() {}

    /**
     * A laid-out box.
     *
     * @param width the box's outer width in columns, including both border columns
     * @param rows  the border, content, cut-bar, and closing rows, top to bottom
     */
    public record Rendered(int width, List<List<Segment>> rows) {

        public Rendered {
            rows = List.copyOf(rows);
        }
    }

    /**
     * Lays out {@code content} as markdown inside a box.
     *
     * @param content   the preview markdown; blank content becomes {@link #NO_PREVIEW}
     * @param maxLines  how many content rows to show before cutting; below 1 is treated as 1
     * @param minHeight pad with blank rows up to this many content rows, capped by {@code maxLines}
     * @param minWidth  the content width floor, shared across a question's options so the box does
     *                  not resize as the selection moves
     * @param maxWidth  the box's outer width ceiling
     */
    public static Rendered render(
            String content, int maxLines, int minHeight, int minWidth, int maxWidth) {
        int outerLimit = Math.max(FRAME_WIDTH, maxWidth);
        int lineLimit = Math.max(1, maxLines);
        int wrapWidth = Math.max(1, outerLimit - FRAME_WIDTH);

        List<List<Segment>> lines = wrappedContent(content, wrapWidth);
        boolean overflowed = lines.size() > lineLimit;
        List<List<Segment>> shown = overflowed ? lines.subList(0, lineLimit) : lines;

        int padTarget = Math.min(Math.max(minHeight, 0), lineLimit);
        int padding = Math.max(0, padTarget - shown.size() - (overflowed ? 1 : 0));

        int contentWidth = Math.max(minWidth, widestOf(shown));
        int boxWidth = Math.max(FRAME_WIDTH, Math.min(contentWidth + FRAME_WIDTH, outerLimit));
        int innerWidth = boxWidth - FRAME_WIDTH;

        List<List<Segment>> rows = new ArrayList<>(shown.size() + padding + 3);
        rows.add(horizontalRow(TableBorders.TOP_LEFT, TableBorders.TOP_RIGHT, boxWidth));
        for (List<Segment> line : shown) rows.add(contentRow(line, innerWidth));
        for (int index = 0; index < padding; index++) rows.add(contentRow(List.of(), innerWidth));
        if (overflowed) rows.add(cutBar(lines.size() - lineLimit, boxWidth));
        rows.add(horizontalRow(TableBorders.BOTTOM_LEFT, TableBorders.BOTTOM_RIGHT, boxWidth));
        return new Rendered(boxWidth, rows);
    }

    /**
     * {@code H$c}'s {@code XFg} loop — the content-width floor every preview box on one card shares,
     * so moving the selection cannot make the frame jitter. It is the widest rendered line across
     * <em>all</em> questions' {@code full} previews, floored at {@link #MIN_WIDTH}.
     *
     * <p>The bundle probes with {@code gtn(markdown, …)}, which takes no width;
     * {@link MarkdownRenderer#render(String)} (its 80-column default overload) is the counterpart.
     */
    public static int sharedMinWidth(List<DisplayQuestion> questions) {
        int widest = MIN_WIDTH;
        for (DisplayQuestion question : questions) {
            for (DisplayQuestion.DisplayOption option : question.options()) {
                if (!(option.preview() instanceof DisplayQuestion.Preview.Full full)) continue;
                String ansi = MarkdownRenderer.shared().render(full.markdown());
                for (List<Segment> line
                        : AnsiToSegments.ansiToLines(ansi, LanternaTheme.inputText())) {
                    widest = Math.max(widest, SegmentLines.width(line));
                }
            }
        }
        return widest;
    }

    private static List<List<Segment>> wrappedContent(String content, int wrapWidth) {
        String markdown = StringUtils.isBlank(content) ? NO_PREVIEW : content;
        String ansi = MarkdownRenderer.shared().render(markdown, wrapWidth);
        List<List<Segment>> rendered =
            AnsiToSegments.ansiToLines(ansi, LanternaTheme.inputText());

        int end = rendered.size();
        while (end > 1 && SegmentLines.width(rendered.get(end - 1)) == 0) end--;

        List<List<Segment>> wrapped = new ArrayList<>(end);
        for (List<Segment> line : rendered.subList(0, end)) {
            wrapped.addAll(SegmentLines.wrap(line, wrapWidth));
        }
        return wrapped;
    }

    private static int widestOf(List<List<Segment>> lines) {
        int widest = 0;
        for (List<Segment> line : lines) widest = Math.max(widest, SegmentLines.width(line));
        return widest;
    }

    private static List<Segment> horizontalRow(char left, char right, int boxWidth) {
        String rule = left + String.valueOf(TableBorders.HORIZONTAL).repeat(boxWidth - 2) + right;
        return SegmentLines.plain(rule, border());
    }

    private static List<Segment> contentRow(List<Segment> line, int innerWidth) {
        List<Segment> clipped = SegmentLines.sliceToWidth(line, innerWidth);
        String padding = " ".repeat(Math.max(0, innerWidth - SegmentLines.width(clipped)));
        List<Segment> row = new ArrayList<>(clipped.size() + 2);
        row.add(new Segment(TableBorders.VERTICAL + " ", border()));
        row.addAll(clipped);
        row.add(new Segment(padding + " " + TableBorders.VERTICAL, border()));
        return List.copyOf(row);
    }

    private static List<Segment> cutBar(int hiddenLines, int boxWidth) {
        String label = "─── " + Figures.SCISSORS + " ─── " + hiddenLines + " lines hidden ";
        int fill = Math.max(0, boxWidth - 2 - FormatUtils.displayWidth(label));
        String bar = TableBorders.MID_LEFT + label
            + String.valueOf(TableBorders.HORIZONTAL).repeat(fill) + TableBorders.MID_RIGHT;
        return SegmentLines.plain(bar, LanternaTheme.toolWarning());
    }

    /** The bundle paints the frame with {@code dimColor}; this theme's dim foreground is inactive. */
    private static TextColor border() {
        return LanternaTheme.welcomeDim();
    }
}
