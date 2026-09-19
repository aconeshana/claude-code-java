package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.effort.EffortHelpers;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.claudecode.ui.lanterna.dialog.EffortSliderLayout.Accent;
import com.claudecode.ui.lanterna.dialog.EffortSliderLayout.Slot;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.components.SmartLayout;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.transcript.MessagePanel;

/**
 * Inline {@code /effort} slider — sits between {@link MessagePanel} and {@link InputPanel} in the
 * SmartLayout stack, occupying zero rows when idle.
 *
 * <p>TS coverage:
 * <ul>
 *   <li>{@code src/commands/effort/effort.tsx} — the effort picker: arrow-key selection, the
 *       labelled track with its marker, per-level label styling (including the
 *       {@code ultracode} slot's inverted violet treatment), and the cost note.</li>
 * </ul>
 *
 * <p>Geometry lives in {@link EffortSliderLayout} and animation maths in
 * {@link EffortSliderEffects}; this class only paints and handles input.
 */
public final class EffortSliderDialog extends Panel implements InlineOverlay {

    /** Cells from the left edge of the body to the first dash of the slider. */
    private static final int LEFT_PAD = 2;

    /** Screen row of the divider that separates the slider from the message stream. */
    private static final int DIVIDER_ROW = 0;

    /** Screen row holding the label strip — also the row the ripple radiates from. */
    private static final int LABEL_ROW = 5;

    /**
     * Screen rows above the label row. Subtracting this maps a screen row into the ripple
     * coordinate space {@link EffortSliderEffects} expects, whose origin row is the labels.
     */
    private static final int HEADER_ROWS = 3;

    /** Rows reserved for the cost-note blurb shown beneath the labels. */
    private static final int NOTE_ROWS = 3;

    private static final ScheduledExecutorService ANIMATION =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "effort-slider-anim");
            t.setDaemon(true);
            return t;
        });

    /**
     * The whole of the slider's mutable state, as one immutable snapshot swapped wholesale.
     *
     * <p>{@code volatile} rather than guarded by this object's monitor: {@link #drawComponent}
     * runs on the Lanterna GUI thread and must never block on the monitor that
     * {@link #handleKey} and {@link #show} hold. A single reference read gives the renderer a
     * self-consistent {@code (layout, selectedIdx)} pair by construction — see
     * {@link EffortSliderState} for why holding them apart was a crash.
     */
    private volatile EffortSliderState state = EffortSliderState.IDLE;

    /** Guarded by {@code this}; only ever touched by the synchronized writers. */
    private ScheduledFuture<?> animation;

    /** Interval {@link #animation} was scheduled at, so a change of accent can reschedule it. */
    private long animationPeriodMs;

    public EffortSliderDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        SliderArea sliderArea = new SliderArea();
        // FILL so the SliderArea spans the full Panel width — without this
        // LinearLayout VERTICAL defaults to BEGINNING horizontal alignment,
        // which clamps the child to its preferredSize width and pins it to
        // column 0. The slider's "centred" look depends on getSize.getColumns
        // being the full terminal width when drawComponent runs.
        sliderArea.setLayoutData(
            LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(sliderArea);
    }

    /**
     * Activate the slider with {@code initial} as the starting selection and the
     * generic unknown-model level list.
     * Must run on the GUI thread.
     *
     * @param initial  current effort level (null defaults to {@code "high"})
     * @param onResult invoked with the chosen level on Enter, or {@code null}
     *                 on Esc / external dismissal. Called from the GUI thread
     *                 immediately before {@link #hide} returns control.
     */
    public synchronized void show(String initial, Consumer<String> onResult) {
        show(initial, EffortHelpers.ORDERED_LEVELS, onResult);
    }

    /** Opens the slider with the active model's actual supported effort levels. */
    public synchronized void show(
            String initial, List<String> supportedLevels, Consumer<String> onResult) {
        show(initial, supportedLevels, false, onResult);
    }

    /**
     * Opens the slider, optionally offering the {@code ultracode} slot.
     *
     * @param withUltracode whether this session may select {@code ultracode}; callers decide
     *                      via {@link EffortHelpers#isUltracodeAvailable}
     */
    public synchronized void show(String initial, List<String> supportedLevels,
            boolean withUltracode, Consumer<String> onResult) {
        List<String> levels = supportedLevels == null || supportedLevels.isEmpty()
            ? EffortHelpers.ORDERED_LEVELS : supportedLevels;
        state = EffortSliderState.opened(
            initial, levels, withUltracode, onResult, System.currentTimeMillis());
        syncAnimation();
        invalidate();
    }

    @Override public boolean isActive() { return state.active(); }

    /**
     * Intercept a key while {@link #isActive}. Sets {@code deliver=false}
     * for any keystroke this dialog consumes (←/→/Enter/Esc/Ctrl+C/Ctrl+D);
     * other keys fall through to whichever component normally gets them.
     *
     * <p>Called from the host {@code WindowListener.onInput}.
     */
    @Override public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        EffortSliderState current = state;
        if (!current.active()) return;
        KeyType t = key.getKeyType();
        if (t == KeyType.ARROW_LEFT || t == KeyType.ARROW_RIGHT) {
            state = current.moved(t == KeyType.ARROW_LEFT ? -1 : 1);
            syncAnimation();
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ENTER) {
            Slot slot = current.selectedSlot();
            resolve(slot == null ? null : slot.value());
            deliver.set(false);
            return;
        }
        if (t == KeyType.ESCAPE) {
            resolve(null);
            deliver.set(false);
            return;
        }
        if (t == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null) {
            char ch = key.getCharacter();
            if (ch == 'c' || ch == 'd') {
                resolve(null);
                deliver.set(false);
            }
        }
    }

    private synchronized void resolve(String level) {
        EffortSliderState current = state;
        if (!current.active()) return;
        Consumer<String> cb = current.onResult();
        hide();
        if (cb != null) cb.accept(level);
    }

    private synchronized void hide() {
        state = state.closed();
        syncAnimation();
        invalidate();
    }

    /**
     * Start or stop the repaint timer so it only runs while an animated level is selected.
     * The timer only calls {@link #invalidate}, which sets a dirty flag and never touches the
     * component tree, so driving it from outside the GUI thread cannot invert lock order with
     * the screen refresh.
     *
     * <p>The interval follows the selected accent: the ripple redraws faster than it advances
     * so the wavefront reads as continuous, while the shimmer and rainbow are driven one repaint
     * per label frame. A single 80 ms clock for all three made {@code labelFrame}'s 100 ms
     * division alias into an uneven 2-1-2 cadence with one repaint in five painting an identical
     * frame; matching the clocks makes those two animations advance exactly one step per redraw.
     */
    private void syncAnimation() {
        EffortSliderState current = state;
        boolean wanted = current.animated();
        long period = current.selectedAccent() == Accent.RIPPLE
            ? EffortSliderEffects.RIPPLE_FRAME_MS : EffortSliderEffects.LABEL_FRAME_MS;
        if (wanted && (animation == null || animation.isDone() || animationPeriodMs != period)) {
            if (animation != null) animation.cancel(false);
            animationPeriodMs = period;
            animation = ANIMATION.scheduleAtFixedRate(
                this::invalidate, 0, period, TimeUnit.MILLISECONDS);
        } else if (!wanted && animation != null) {
            animation.cancel(false);
            animation = null;
            animationPeriodMs = 0;
        }
    }

    /**
     * Collapse to zero size while idle so the parent {@link SmartLayout} hands
     * those rows back to {@link MessagePanel}. Same pattern as
     * {@link PermissionDialog#calculatePreferredSize}.
     *
     * <p>Unsynchronized: this runs during layout on the GUI thread and only reads one
     * {@link EffortSliderState} snapshot, so it must not contend with {@link #handleKey}.
     */
    @Override
    public TerminalSize calculatePreferredSize() {
        EffortSliderState s = state;
        if (!s.active()) return new TerminalSize(0, 0);
        TerminalSize parent = super.calculatePreferredSize();
        // Wide enough for the slider plus padding; defer to parent for width
        // when it's larger (so the slider expands to fill the terminal).
        int cols = Math.max(LEFT_PAD * 2 + s.layout().width(), parent.getColumns());
        return new TerminalSize(cols, s.bodyRows());
    }

    /** Suppress focus traversal while idle; matches PermissionDialog. */
    @Override public Interactable nextFocus(Interactable fromThis) {
        return state.active() ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return state.active() ? super.previousFocus(fromThis) : null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Custom-drawn slider body
    // ──────────────────────────────────────────────────────────────────────

    /**
     * The slider body — drawn as a single custom component so we can paint per-character
     * effects (rainbow, shimmer and ripple) that a Lanterna
     * {@link com.googlecode.lanterna.gui2.Label} cannot express, since labels take a single
     * foreground colour.
     */
    private final class SliderArea extends AbstractComponent<SliderArea> {
        @Override protected ComponentRenderer<SliderArea> createDefaultRenderer() {
            return new SliderRenderer();
        }
    }

    private final class SliderRenderer implements ComponentRenderer<SliderArea> {

        @Override
        public TerminalSize getPreferredSize(SliderArea c) {
            EffortSliderState s = state;
            return new TerminalSize(LEFT_PAD * 2 + s.layout().width(), s.bodyRows());
        }

        @Override
        public void drawComponent(TextGUIGraphics g, SliderArea c) {
            // One read of the volatile snapshot for the whole frame: every field below came
            // from the same write, so the layout and the selection cannot disagree.
            EffortSliderState s = state;
            if (!s.active()) return;  // calculatePreferredSize collapses us; guard anyway
            g.fill(' ');

            EffortSliderLayout layout = s.layout();
            TerminalSize size = g.getSize();
            int width = layout.width();
            // Centre the slider horizontally — the body component stretches to full
            // terminal width (LinearLayout.Alignment.FILL), so we compute leftX
            // inside drawComponent rather than relying on Panel positioning.
            int leftX = Math.max(LEFT_PAD, (size.getColumns() - width) / 2);
            int marker = s.markerColumn();
            // The ripple washes over the whole strip, but only while ultracode is selected.
            Ripple ripple = s.selectedAccent() == Accent.RIPPLE
                ? new Ripple(marker,
                    EffortSliderEffects.travel(System.currentTimeMillis() - s.openedAtMs()))
                : null;

            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, DIVIDER_ROW, "─".repeat(Math.max(0, size.getColumns())));

            drawTitle(g, leftX, width, ripple);
            drawFraming(g, leftX, width, ripple);
            drawTrack(g, s, leftX, marker, ripple);
            drawLabels(g, s, leftX, width, ripple);
            int y = drawSublabel(g, layout, leftX, width, ripple);
            y = drawNotes(g, s, leftX, y);

            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.disableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, Math.min(y, size.getRows() - 1),
                "←/→ to adjust · Enter to confirm · Esc to cancel");
        }

        /** Row 1 — the "Effort" heading. */
        private void drawTitle(TextGUIGraphics g, int leftX, int width, Ripple ripple) {
            if (ripple != null) {
                putRippled(g, leftX, 1, pad("Effort", width), ripple, false, null);
                return;
            }
            g.setForegroundColor(LanternaTheme.statusCost());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Effort");
            g.disableModifiers(SGR.BOLD);
        }

        /** Rows 2-3 — a blank row, then the "Faster … Smarter" framing spanning the track. */
        private void drawFraming(TextGUIGraphics g, int leftX, int width, Ripple ripple) {
            String framing = "Faster"
                + " ".repeat(Math.max(0, width - "Faster".length() - "Smarter".length()))
                + "Smarter";
            if (ripple != null) {
                putRippled(g, leftX, 2, " ".repeat(width), ripple, false, null);
                putRippled(g, leftX, 3, framing, ripple, false, null);
                return;
            }
            // Upstream leaves this text uncoloured, so it follows the terminal foreground.
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
            g.disableModifiers(SGR.BOLD);
            g.putString(leftX, 3, framing);
        }

        /** Row 4 — the dashed track with the selection marker punched into it. */
        private void drawTrack(TextGUIGraphics g, EffortSliderState s, int leftX, int marker,
                Ripple ripple) {
            String track = s.layout().trackChars();
            int accentStart = s.layout().accentStart();
            for (int i = 0; i < track.length(); i++) {
                if (i == marker) continue;
                if (ripple != null) {
                    putRippled(g, leftX, 4, String.valueOf(track.charAt(i)), ripple, true,
                        EffortSliderEffects.SHIMMER_PEAK, i);
                    continue;
                }
                g.setForegroundColor(i >= accentStart
                    ? EffortSliderEffects.VIOLET : LanternaTheme.welcomeDim());
                g.putString(leftX + i, 4, String.valueOf(track.charAt(i)));
            }
            g.enableModifiers(SGR.BOLD);
            if (ripple != null) {
                g.setBackgroundColor(EffortSliderEffects.VIOLET);
                g.setForegroundColor(EffortSliderEffects.RIPPLE_TEXT);
            } else {
                g.setForegroundColor(marker >= accentStart
                    ? EffortSliderEffects.VIOLET : TextColor.ANSI.DEFAULT);
            }
            g.putString(leftX + marker, 4, "▲");
            g.disableModifiers(SGR.BOLD);
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        }

        /** Row 5 — the level labels, left-anchored at their computed starts. */
        private void drawLabels(TextGUIGraphics g, EffortSliderState s, int leftX, int width,
                Ripple ripple) {
            EffortSliderLayout layout = s.layout();
            List<Slot> slots = layout.slots();
            if (ripple != null) {
                // The gaps between labels ripple too, so lay the whole row out in one pass.
                StringBuilder row = new StringBuilder(" ".repeat(width));
                for (int i = 0; i < slots.size(); i++) {
                    String label = slots.get(i).value();
                    int start = layout.labelStarts().get(i);
                    if (start + label.length() <= width) {
                        row.replace(start, start + label.length(), label);
                    }
                }
                putRippled(g, leftX, LABEL_ROW, row.toString(), ripple, true, null);
            }
            for (int i = 0; i < slots.size(); i++) {
                Slot slot = slots.get(i);
                int x = leftX + layout.labelStarts().get(i);
                if (i == s.selectedIdx()) {
                    drawSelectedLabel(g, x, slot, s.openedAtMs());
                } else if (ripple == null) {
                    drawUnselectedLabel(g, x, slot);
                }
            }
        }

        /** Unselected labels are dim — except ultracode, the one level that keeps its colour. */
        private void drawUnselectedLabel(TextGUIGraphics g, int x, Slot slot) {
            g.disableModifiers(SGR.BOLD);
            g.setForegroundColor(slot.accent() == Accent.RIPPLE
                ? EffortSliderEffects.VIOLET : LanternaTheme.welcomeDim());
            g.putString(x, LABEL_ROW, slot.value());
        }

        /** Paint the selected label in its signature colour or animation. */
        private void drawSelectedLabel(TextGUIGraphics g, int x, Slot slot, long openedAtMs) {
            String text = slot.value();
            long frame = EffortSliderEffects.labelFrame(System.currentTimeMillis() - openedAtMs);
            switch (slot.accent()) {
                case RIPPLE -> {
                    // The only level with a filled background — it reads as a lit button.
                    g.enableModifiers(SGR.BOLD);
                    g.setBackgroundColor(EffortSliderEffects.VIOLET);
                    g.setForegroundColor(EffortSliderEffects.RIPPLE_TEXT);
                    g.putString(x, LABEL_ROW, text);
                    g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                    g.disableModifiers(SGR.BOLD);
                }
                case RAINBOW -> {
                    g.enableModifiers(SGR.BOLD);
                    for (int i = 0; i < text.length(); i++) {
                        g.setForegroundColor(LanternaTheme.rainbow((int) (i + frame)));
                        g.putString(x + i, LABEL_ROW, String.valueOf(text.charAt(i)));
                    }
                    g.disableModifiers(SGR.BOLD);
                }
                case SHIMMER -> {
                    int crest = EffortSliderEffects.shimmerCrest(text.length(), frame);
                    for (int i = 0; i < text.length(); i++) {
                        boolean peak = i == crest;
                        if (peak || i == crest - 1 || i == crest + 1) {
                            g.enableModifiers(SGR.BOLD);
                        } else {
                            g.disableModifiers(SGR.BOLD);
                        }
                        g.setForegroundColor(peak
                            ? EffortSliderEffects.SHIMMER_PEAK : LanternaTheme.acceptPurple());
                        g.putString(x + i, LABEL_ROW, String.valueOf(text.charAt(i)));
                    }
                    g.disableModifiers(SGR.BOLD);
                }
                default -> {
                    g.enableModifiers(SGR.BOLD);
                    g.setForegroundColor(accentColor(slot.accent()));
                    g.putString(x, LABEL_ROW, text);
                    g.disableModifiers(SGR.BOLD);
                }
            }
        }

        /** Row 6 — the ultracode sublabel, when that slot is present. Returns the next free row. */
        private int drawSublabel(TextGUIGraphics g, EffortSliderLayout layout, int leftX,
                int width, Ripple ripple) {
            if (layout.sublabelText() == null) return LABEL_ROW + 2;
            int y = LABEL_ROW + 1;
            int start = layout.sublabelStart();
            if (ripple != null) {
                putRippled(g, leftX, y, pad(" ".repeat(start) + layout.sublabelText(), width),
                    ripple, true, null);
            } else {
                g.disableModifiers(SGR.BOLD);
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(leftX + start, y, layout.sublabelText());
            }
            return y + 2;
        }

        /** The cost note for the current selection. Returns the footer row. */
        private int drawNotes(TextGUIGraphics g, EffortSliderState s, int leftX, int y) {
            g.disableModifiers(SGR.BOLD);
            g.setForegroundColor(LanternaTheme.welcomeDim());
            String note = s.costNote();
            if (note != null) {
                wrapAndDraw(g, note, s.layout().width(), leftX, y);
                y += NOTE_ROWS + 1;
            }
            return y;
        }

        private TextColor accentColor(Accent accent) {
            return switch (accent) {
                case WARNING -> LanternaTheme.toolWarning();
                case SUCCESS -> LanternaTheme.toolSuccess();
                case PERMISSION -> LanternaTheme.permission();
                case SHIMMER -> LanternaTheme.acceptPurple();
                case RIPPLE -> EffortSliderEffects.VIOLET;
                default -> LanternaTheme.welcomeDim();
            };
        }

        private String pad(String text, int width) {
            return text.length() >= width ? text : text + " ".repeat(width - text.length());
        }

        private void putRippled(TextGUIGraphics g, int leftX, int y, String text,
                Ripple ripple, boolean dim, TextColor coveredColor) {
            putRippled(g, leftX, y, text, ripple, dim, coveredColor, 0);
        }

        /**
         * Draw {@code text} with the ripple applied per character. {@code startCol} is the
         * text's offset from the slider's left edge, which is the ripple's coordinate origin.
         */
        private void putRippled(TextGUIGraphics g, int leftX, int y, String text,
                Ripple ripple, boolean dim, TextColor coveredColor, int startCol) {
            int rippleRow = y - HEADER_ROWS;
            g.disableModifiers(SGR.BOLD);
            for (int i = 0; i < text.length(); i++) {
                int col = startCol + i;
                int stop = EffortSliderEffects.rampIndex(
                    EffortSliderEffects.distance(col, rippleRow, ripple.originCol()),
                    ripple.travel());
                if (stop < 0) {
                    g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                    g.setForegroundColor(
                        dim ? LanternaTheme.welcomeDim() : TextColor.ANSI.DEFAULT);
                } else {
                    g.setBackgroundColor(EffortSliderEffects.RIPPLE_RAMP.get(stop));
                    g.setForegroundColor(coveredColor != null
                        ? coveredColor : EffortSliderEffects.RIPPLE_TEXT);
                }
                g.putString(leftX + col, y, String.valueOf(text.charAt(i)));
            }
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        }

        /**
         * Word-wrap {@code text} into up to {@link #NOTE_ROWS} rows of at most the slider width,
         * drawn left-aligned at column {@code x} starting at row {@code y}. Truncates with no
         * marker when the text overflows.
         */
        private void wrapAndDraw(TextGUIGraphics g, String text, int width, int x, int y) {
            String[] words = text.split("\\s+");
            StringBuilder line = new StringBuilder();
            int row = 0;
            for (String w : words) {
                int needed = line.length() + (line.isEmpty() ? 0 : 1) + w.length();
                if (needed > width && !line.isEmpty()) {
                    g.putString(x, y + row, line.toString());
                    row++;
                    if (row >= NOTE_ROWS) return;
                    line.setLength(0);
                }
                if (!line.isEmpty()) line.append(' ');
                line.append(w);
            }
            // row < NOTE_ROWS here by construction — the loop returns early on overflow.
            if (!line.isEmpty()) {
                g.putString(x, y + row, line.toString());
            }
        }
    }

    /** One frame of the violet ripple: where it radiates from and how far it has spread. */
    private record Ripple(int originCol, double travel) {}
}
