package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.effort.EffortHelpers;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry for the inline {@code /effort} slider: where each label starts, where its
 * marker sits, how wide the strip is, and which characters form the track.
 *
 * <p>TS coverage:
 * <ul>
 *   <li>{@code src/commands/effort/effort.tsx} — the effort picker's layout helpers:
 *       per-level-count geometry, the label-start prefix sums, and the variant that appends
 *       the {@code ultracode} slot together with its accent track segment and sublabel.</li>
 * </ul>
 *
 * <p>Authoritative constants come from the 2.1.236 bundle ({@code l2w}, {@code c2w},
 * {@code s2w}, {@code a2w}, {@code itc}). Upstream hardcodes the marker columns as
 * {@code [1,10,20,30,40]}; this derives them as {@code labelStart + (labelLength - 1) / 2},
 * which reproduces that array exactly for every prefix of the standard level list while also
 * handling the shorter {@code none}/{@code minimal} lists that GPT-family models report —
 * those levels have no upstream slot, so no hardcoded column exists for them.
 */
record EffortSliderLayout(
        List<Slot> slots,
        int width,
        List<Integer> labelStarts,
        List<Integer> markerColumns,
        String trackChars,
        /** First column of the violet accent segment; equals {@link #width} when absent. */
        int accentStart,
        /** {@code null} unless the {@code ultracode} slot is present. */
        String sublabelText,
        int sublabelStart) {

    /** One selectable slot. Upstream's label is always identical to its value. */
    record Slot(String value, Accent accent) {
        int length() { return value.length(); }
    }

    /**
     * Colour role for a slot, mirroring the {@code color} field of upstream's level table.
     * {@link #PLAIN} has no upstream counterpart — it covers {@code none} and {@code minimal},
     * which only appear for GPT-family models.
     */
    enum Accent { PLAIN, WARNING, SUCCESS, PERMISSION, SHIMMER, RAINBOW, RIPPLE }

    /** Gaps between adjacent labels, indexed by the left label's position. */
    private static final int[] SPACERS = {5, 5, 5, 6};

    /** Lower bound on the strip width, so a one- or two-slot slider still reads as a slider. */
    private static final int MIN_WIDTH = 14;

    /** Gap between {@code max} and {@code ultracode} — wider, to clear the {@code ┆} divider. */
    private static final int ULTRACODE_SPACER = 7;

    private static final char TRACK = '─';

    /** Separates the regular levels from the {@code ultracode} slot. */
    private static final char ULTRACODE_DIVIDER = '┆';

    /** Track cells to the right of the divider. */
    private static final int ACCENT_TRACK_LENGTH = 18;

    /**
     * Builds the layout for {@code levels}, appending the {@code ultracode} slot when the
     * session may select it.
     *
     * @param levels           ordered levels the active model supports
     * @param withUltracode    whether to append the {@code ultracode} slot
     */
    static EffortSliderLayout compute(List<String> levels, boolean withUltracode) {
        List<Slot> slots = new ArrayList<>();
        List<Integer> labelStarts = new ArrayList<>();
        // Scratch bookkeeping: the count drives the SPACERS lookup and the cursor advance.
        List<Integer> spacers = new ArrayList<>();
        int cursor = 0;
        for (String level : levels) {
            if (!slots.isEmpty()) {
                int spacer = SPACERS[Math.min(spacers.size(), SPACERS.length - 1)];
                spacers.add(spacer);
                cursor += slots.getLast().length() + spacer;
            }
            slots.add(new Slot(level, accentFor(level)));
            labelStarts.add(cursor);
        }
        int baseWidth = slots.isEmpty()
            ? MIN_WIDTH
            : Math.max(labelStarts.getLast() + slots.getLast().length(), MIN_WIDTH);

        if (!withUltracode) {
            return new EffortSliderLayout(
                List.copyOf(slots), baseWidth, List.copyOf(labelStarts),
                markerColumns(slots, labelStarts),
                String.valueOf(TRACK).repeat(baseWidth), baseWidth, null, 0);
        }

        if (!slots.isEmpty()) {
            spacers.add(ULTRACODE_SPACER);
            cursor += slots.getLast().length() + ULTRACODE_SPACER;
        }
        slots.add(new Slot(EffortHelpers.ULTRACODE, Accent.RIPPLE));
        labelStarts.add(cursor);

        List<Integer> markers = new ArrayList<>(markerColumns(slots, labelStarts));
        // Upstream pins the ultracode marker to an offset from the pre-append width rather
        // than centring it under its label. The two agree whenever the regular levels alone
        // already exceed MIN_WIDTH, and this keeps the marker on the accent track when they
        // do not.
        markers.set(markers.size() - 1, baseWidth + 11);

        String track = String.valueOf(TRACK).repeat(baseWidth + 1)
            + ULTRACODE_DIVIDER
            + String.valueOf(TRACK).repeat(ACCENT_TRACK_LENGTH);
        return new EffortSliderLayout(
            List.copyOf(slots), baseWidth + 20, List.copyOf(labelStarts),
            List.copyOf(markers), track,
            baseWidth + 2, EffortHelpers.ULTRACODE_SUBLABEL, baseWidth + 3);
    }

    private static List<Integer> markerColumns(List<Slot> slots, List<Integer> labelStarts) {
        List<Integer> markers = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            markers.add(labelStarts.get(i) + (slots.get(i).length() - 1) / 2);
        }
        return List.copyOf(markers);
    }

    private static Accent accentFor(String level) {
        return switch (level) {
            case "low" -> Accent.WARNING;
            case "medium" -> Accent.SUCCESS;
            case "high" -> Accent.PERMISSION;
            case "xhigh" -> Accent.SHIMMER;
            case "max" -> Accent.RAINBOW;
            case EffortHelpers.ULTRACODE -> Accent.RIPPLE;
            default -> Accent.PLAIN;
        };
    }
}
