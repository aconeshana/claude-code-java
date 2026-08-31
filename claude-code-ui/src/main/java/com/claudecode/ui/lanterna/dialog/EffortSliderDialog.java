package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.components.SmartLayout;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.transcript.MessagePanel;

/**
 * Inline {@code /effort} slider — sits between {@link MessagePanel} and {@link InputPanel} in the
 * SmartLayout stack, occupying zero rows when idle.
 */
public final class EffortSliderDialog extends Panel implements InlineOverlay {

    /** Ordered levels for the active model; replaced each time the overlay opens. */
    private List<String> levels = EffortHelpers.ORDERED_LEVELS;

    /** Cells from the left edge of the body to the first dash of the slider. */
    private static final int LEFT_PAD = 2;

    /** Width of the dashed slider line. Wide enough for "Faster"/"Smarter" framing. */
    private static final int SLIDER_WIDTH = 45;

    /**
     * Body height in rows: blank + Effort + blank + Faster/Smarter + slider +
     * labels + blank + footer. Warning rows (max) are part of the same block
     * but we keep BODY_ROWS conservative; renderer truncates if needed.
     */
    private static final int BODY_ROWS = 8;

    /** Optional rows for the max-only warning blurb. */
    private static final int WARNING_ROWS = 3;

    private boolean active;
    private int selectedIdx;
    private Consumer<String> onResult;

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
        this.levels = supportedLevels == null || supportedLevels.isEmpty()
            ? EffortHelpers.ORDERED_LEVELS : List.copyOf(supportedLevels);
        int idx = levels.indexOf(initial == null ? "high" : initial);
        int defaultIndex = levels.indexOf("high");
        this.selectedIdx = idx >= 0 ? idx : Math.max(0, defaultIndex);
        this.onResult = onResult;
        this.active = true;
        invalidate();
    }

    @Override public boolean isActive() { return active; }

    /**
     * Intercept a key while {@link #isActive}. Sets {@code deliver=false}
     * for any keystroke this dialog consumes (←/→/Enter/Esc/Ctrl+C/Ctrl+D);
     * other keys fall through to whichever component normally gets them.
     *
     * <p>Called from the host {@code WindowListener.onInput}.
     */
    @Override public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        KeyType t = key.getKeyType();
        if (t == KeyType.ARROW_LEFT) {
            selectedIdx = InlineOverlay.cycleIndex(selectedIdx, -1, levels.size());
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_RIGHT) {
            selectedIdx = InlineOverlay.cycleIndex(selectedIdx, 1, levels.size());
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ENTER) {
            resolve(levels.get(selectedIdx));
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
        if (!active) return;
        Consumer<String> cb = onResult;
        hide();
        if (cb != null) cb.accept(level);
    }

    private synchronized void hide() {
        active = false;
        onResult = null;
        invalidate();
    }

    /**
     * Collapse to zero size while idle so the parent {@link SmartLayout} hands
     * those rows back to {@link MessagePanel}. Same pattern as
     * {@link PermissionDialog#calculatePreferredSize}.
     */
    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        int rows = BODY_ROWS;
        if (!StringUtils.isBlank(EffortHelpers.getEffortLevelWarning(levels.get(selectedIdx)))) {
            rows += WARNING_ROWS;
        }
        TerminalSize parent = super.calculatePreferredSize();
        // Wide enough for the slider plus padding; defer to parent for width
        // when it's larger (so the slider expands to fill the terminal).
        int cols = Math.max(LEFT_PAD * 2 + SLIDER_WIDTH, parent.getColumns());
        return new TerminalSize(cols, rows);
    }

    /** Suppress focus traversal while idle; matches PermissionDialog. */
    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Custom-drawn slider body
    // ──────────────────────────────────────────────────────────────────────

    /**
     * The slider body — drawn as a single custom component so we can paint
     * a per-character rainbow for the {@code max} label (which a Lanterna
     * {@link com.googlecode.lanterna.gui2.Label} cannot do — labels take a
     * single foreground colour).
     */
    private final class SliderArea extends AbstractComponent<SliderArea> {
        @Override protected ComponentRenderer<SliderArea> createDefaultRenderer() {
            return new SliderRenderer();
        }
    }

    private final class SliderRenderer implements ComponentRenderer<SliderArea> {

        @Override
        public TerminalSize getPreferredSize(SliderArea c) {
            int rows = BODY_ROWS;
            if (active && !StringUtils.isBlank(EffortHelpers.getEffortLevelWarning(
                levels.get(selectedIdx)))) {
                rows += WARNING_ROWS;
            }
            return new TerminalSize(LEFT_PAD * 2 + SLIDER_WIDTH, rows);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, SliderArea c) {
            if (!active) return;  // calculatePreferredSize collapses us; guard anyway
            g.fill(' ');

            TerminalSize size = g.getSize();
// Centre the slider horizontally — the body component stretches to full
// terminal width (LinearLayout.Alignment.FILL), so we compute sliderLeftX
// inside drawComponent rather than relying on Panel positioning.
            int sliderLeftX = Math.max(LEFT_PAD, (size.getColumns() - SLIDER_WIDTH) / 2);

            // Row 0: full-width divider — separates the slider from the
            // message stream above. Matches the screenshot: a thin ─ line
            // running edge-to-edge.
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, size.getColumns())));

            // Row 1: "  Effort" (bold, theme warning)
            g.setForegroundColor(LanternaTheme.statusCost());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Effort");
            g.disableModifiers(SGR.BOLD);

            // Row 2: blank.

            // Row 3: "Faster" left of slider, "Smarter" right edge (green).
            g.setForegroundColor(LanternaTheme.statusCost());
            g.putString(sliderLeftX, 3, "Faster");
            int smarterX = sliderLeftX + SLIDER_WIDTH - "Smarter".length();
            g.putString(smarterX, 3, "Smarter");

            // Compute slot centres so the marker and labels line up.
            int[] slotCenters = new int[levels.size()];
            for (int i = 0; i < levels.size(); i++) {
                slotCenters[i] = sliderLeftX
                    + (i * (SLIDER_WIDTH - 1)) / Math.max(1, levels.size() - 1);
            }

            // Row 4: slider line — dashes plus ▲ at the selected slot.
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(sliderLeftX, 4, "─".repeat(SLIDER_WIDTH));
            g.setForegroundColor(levelColor(levels.get(selectedIdx)));
            g.putString(slotCenters[selectedIdx], 4, "▲");

            // Row 5: level labels — selected one coloured, others dim.
            // Each slot shows "○ low", "◐ medium" etc (symbol + space + name),


            for (int i = 0; i < levels.size(); i++) {
                String level = levels.get(i);
                String labelText = EffortHelpers.effortLevelToSymbol(level) + " " + level;
                int x = slotCenters[i] - labelText.length() / 2;
                if (x < 0) x = 0;
                if (i == selectedIdx) {
                    drawSelectedLabel(g, x, level, labelText);
                } else {
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(x, 5, labelText);
                }
            }

            // Optional warning block (max only). Sits between labels and footer.
            int footerY = 7;
            String warning = EffortHelpers.getEffortLevelWarning(levels.get(selectedIdx));
            if (!StringUtils.isBlank(warning)) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                wrapAndDraw(g, warning, sliderLeftX);
                footerY = 7 + WARNING_ROWS + 1;
            }

            // Footer keys (dim).
            g.setForegroundColor(LanternaTheme.welcomeDim());
            String footer = "←/→ to adjust · Enter to confirm · Esc to cancel";
            int safeFooterY = Math.min(footerY, size.getRows() - 1);
            g.putString(LEFT_PAD, safeFooterY, footer);
        }

        /**
 * Paint the selected label on the labels row (row 5) in its signature colour.
         */
        private void drawSelectedLabel(TextGUIGraphics g, int x, String level, String labelText) {
            final int y = 5;  // labels row — fixed offset within the slider body
            if (Strings.CS.equals("max", level)) {
                TextColor[] stops = {
                    new TextColor.RGB(80, 186, 102),   // ◉: green
                    new TextColor.RGB(80, 186, 102),   //  : green (space)
                    new TextColor.RGB(80, 160, 220),   // m: blue
                    new TextColor.RGB(80, 160, 220),   // a: blue
                    new TextColor.RGB(175, 135, 254),  // x: purple
                };
                g.enableModifiers(SGR.BOLD);
                for (int i = 0; i < labelText.length(); i++) {
                    g.setForegroundColor(stops[Math.min(i, stops.length - 1)]);
                    g.putString(x + i, y, String.valueOf(labelText.charAt(i)));
                }
                g.disableModifiers(SGR.BOLD);
            } else {
                g.setForegroundColor(levelColor(level));
                g.enableModifiers(SGR.BOLD);
                g.putString(x, y, labelText);
                g.disableModifiers(SGR.BOLD);
            }
        }

        /**
         * Word-wrap {@code text} into up to {@link #WARNING_ROWS} rows of at most
         * {@link #SLIDER_WIDTH} cells each, drawn left-aligned at column {@code x}
         * starting at the warning-block row (offset 7 within the slider body).
         * Truncates with no marker when the text overflows {@link #WARNING_ROWS}.
         * Only used for the max-only warning blurb, so all row/col limits and
         * the y origin are fixed slider-body constants.
         */
        private void wrapAndDraw(TextGUIGraphics g, String text, int x) {
            final int y = 7;  // warning-block row — fixed offset within the slider body
            String[] words = text.split("\\s+");
            StringBuilder line = new StringBuilder();
            int row = 0;
            for (String w : words) {
                int needed = line.length() + (line.isEmpty() ? 0 : 1) + w.length();
                if (needed > SLIDER_WIDTH && !line.isEmpty()) {
                    g.putString(x, y + row, line.toString());
                    row++;
                    if (row >= WARNING_ROWS) return;
                    line.setLength(0);
                }
                if (!line.isEmpty()) line.append(' ');
                line.append(w);
            }
            // row < WARNING_ROWS here by construction — the loop returns early on overflow.
            if (!line.isEmpty()) {
                g.putString(x, y + row, line.toString());
            }
        }

        /**
         * Effort level → indicator color.
         */
        private TextColor levelColor(String level) {
            return switch (level) {
                case "none", "minimal", "low" -> new TextColor.RGB(254, 192, 9);
                case "medium" -> new TextColor.RGB(80, 186, 102);
                case "high"   -> new TextColor.RGB(178, 185, 248);
                case "xhigh"  -> new TextColor.RGB(175, 135, 254);
                case "max"    -> new TextColor.RGB(131, 170, 220);
                default       -> LanternaTheme.welcomeDim();
            };
        }
    }
}
