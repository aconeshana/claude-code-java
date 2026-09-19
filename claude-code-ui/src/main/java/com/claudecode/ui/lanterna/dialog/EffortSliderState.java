package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.effort.EffortHelpers;

import java.util.List;
import java.util.function.Consumer;
import com.claudecode.ui.lanterna.dialog.EffortSliderLayout.Accent;
import com.claudecode.ui.lanterna.dialog.EffortSliderLayout.Slot;

/**
 * One immutable, self-consistent snapshot of the {@code /effort} slider's state.
 *
 * <p>{@code active}, {@code layout} and {@code selectedIdx} participate in a single invariant:
 * {@code selectedIdx} only means anything relative to the {@code layout} it was chosen against.
 * Holding them as separate mutable fields let a render on the Lanterna GUI thread observe a new
 * {@code layout} alongside the previous selection — reopening {@code /effort} on a model with
 * fewer levels (7 slots for gpt-5.6 with workflows, 3 for opus-4-5) then threw
 * {@code IndexOutOfBoundsException} out of {@code markerColumns().get(selectedIdx)} and killed
 * the GUI thread. Bundling them into one record published through a single {@code volatile}
 * reference makes a torn pair unrepresentable: a reader takes one reference and every field it
 * then reads came from the same write. The render path needs no lock and no bounds check.
 *
 * <p>Every accessor is a pure function of the snapshot, so the renderer never re-reads shared
 * state mid-frame.
 */
record EffortSliderState(
        boolean active,
        EffortSliderLayout layout,
        int selectedIdx,
        long openedAtMs,
        Consumer<String> onResult) {

    /** Rows reserved for the cost-note blurb shown beneath the labels. */
    private static final int NOTE_ROWS = 3;

    /** The idle snapshot: collapsed to zero rows, with a layout present so geometry never nulls. */
    static final EffortSliderState IDLE = new EffortSliderState(
        false, EffortSliderLayout.compute(EffortHelpers.ORDERED_LEVELS, false), 0, 0L, null);

    EffortSliderState {
        if (layout == null) throw new IllegalArgumentException("layout must not be null");
        // Clamped rather than rejected: a caller-supplied initial level that is absent from the
        // level list is a legitimate input, and the constructor is the one place that can
        // guarantee the pair agrees for every reader downstream.
        int slotCount = layout.slots().size();
        selectedIdx = slotCount == 0 ? 0 : Math.clamp(selectedIdx, 0, slotCount - 1);
    }

    /** Opens the slider on {@code levels}, selecting {@code initial} (or {@code high}). */
    static EffortSliderState opened(String initial, List<String> levels, boolean withUltracode,
            Consumer<String> onResult, long nowMs) {
        EffortSliderLayout layout = EffortSliderLayout.compute(levels, withUltracode);
        return new EffortSliderState(
            true, layout, initialIndex(layout, initial), nowMs, onResult);
    }

    /** The idle snapshot, keeping the layout so a final repaint after {@code hide} stays sane. */
    EffortSliderState closed() {
        return new EffortSliderState(false, layout, selectedIdx, openedAtMs, null);
    }

    /** Moves the selection by {@code delta}, wrapping at both ends. */
    EffortSliderState moved(int delta) {
        return new EffortSliderState(active, layout,
            Math.floorMod(selectedIdx + delta, Math.max(1, layout.slots().size())),
            openedAtMs, onResult);
    }

    private static int initialIndex(EffortSliderLayout layout, String initial) {
        String wanted = initial == null ? "high" : initial;
        List<Slot> slots = layout.slots();
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).value().equals(wanted)) return i;
        }
        for (int i = 0; i < slots.size(); i++) {
            if (Strings.CS.equals("high", slots.get(i).value())) return i;
        }
        return 0;
    }

    /** The selected slot, or {@code null} when the level list is empty. */
    Slot selectedSlot() {
        List<Slot> slots = layout.slots();
        return slots.isEmpty() ? null : slots.get(selectedIdx);
    }

    Accent selectedAccent() {
        Slot slot = selectedSlot();
        return slot == null ? Accent.PLAIN : slot.accent();
    }

    /** Column the selection marker sits on. */
    int markerColumn() {
        List<Integer> markers = layout.markerColumns();
        return markers.isEmpty() ? 0 : markers.get(selectedIdx);
    }

    /** The cost note for the current selection; {@code ultracode} deliberately has none. */
    String costNote() {
        Slot slot = selectedSlot();
        if (slot == null || slot.accent() == Accent.RIPPLE) return null;
        String note = EffortHelpers.getEffortLevelWarning(slot.value());
        return StringUtils.isBlank(note) ? null : note;
    }

    /** Whether a repaint timer should be running for the current selection. */
    boolean animated() {
        return active && switch (selectedAccent()) {
            case RIPPLE, SHIMMER, RAINBOW -> true;
            default -> false;
        };
    }

    int bodyRows() {
        // divider, Effort, blank, Faster/Smarter, track, labels, blank, footer
        int rows = 8;
        if (layout.sublabelText() != null) rows++;
        if (costNote() != null) rows += NOTE_ROWS;
        return rows;
    }
}
