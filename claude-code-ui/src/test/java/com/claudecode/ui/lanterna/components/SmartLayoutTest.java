package com.claudecode.ui.lanterna.components;

import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartLayoutTest {

    @Test
    void inactiveOverlaysAreNotMeasuredAndOnlyActivationChangesDirtyTheLayout() {
        SmartLayout layout = new SmartLayout();
        CountingComponent messages = new CountingComponent(10, 3);
        CountingOverlay overlay = new CountingOverlay(20, 8);
        CountingComponent input = new CountingComponent(10, 2);
        List<Component> components =
            List.of(messages, overlay, input);

        layout.doLayout(new TerminalSize(80, 24), components);

        assertEquals(0, overlay.preferredSizeCalls);
        assertEquals(1, input.preferredSizeCalls);
        assertFalse(overlay.isVisible());
        assertFalse(layout.hasChanged());

        overlay.active = true;
        overlay.invalidate();
        assertTrue(layout.hasChanged());

        layout.doLayout(new TerminalSize(80, 24), components);

        assertEquals(1, overlay.preferredSizeCalls);
        assertTrue(overlay.isVisible());
        assertEquals(new TerminalSize(80, 22), messages.getSize(),
            "opening an inline picker must overlay the transcript instead of reflowing it");
        assertEquals(new TerminalPosition(0, 14), overlay.getPosition());
        assertEquals(new TerminalSize(80, 8), overlay.getSize());
        assertEquals(new TerminalPosition(0, 22), input.getPosition());
        assertFalse(layout.hasChanged());
    }

    @Test
    void flowStyleLocalJsxConsumesRowsAboveTheInput() {
        SmartLayout layout = new SmartLayout();
        CountingComponent messages = new CountingComponent(10, 3);
        CountingFlowOverlay btw = new CountingFlowOverlay(20, 8);
        CountingComponent input = new CountingComponent(10, 2);
        btw.active = true;

        layout.doLayout(new TerminalSize(80, 24), List.of(messages, btw, input));

        assertEquals(new TerminalSize(80, 14), messages.getSize());
        assertEquals(new TerminalPosition(0, 14), btw.getPosition());
        assertEquals(new TerminalSize(80, 8), btw.getSize());
        assertEquals(new TerminalPosition(0, 22), input.getPosition());
    }

    @Test
    void hiddenSpinnerCollapsesAndReturnsItsRowsToTheTranscript() {
        // The real SpinnerComponent's calculatePreferredSize() returns EMPTY_SIZE when
        // not visible, so SmartLayout must not reserve a row for it.
        SpinnerComponent spinner = new SpinnerComponent();
        spinner.setVisible(false);
        assertEquals(TerminalSize.of(0, 0), spinner.getPreferredSize(),
            "a hidden spinner contributes no layout size");

        SmartLayout layout = new SmartLayout();
        CountingComponent messages = new CountingComponent(10, 3);
        CountingComponent input = new CountingComponent(10, 2);

        layout.doLayout(new TerminalSize(80, 24), List.of(messages, spinner, input));

        assertEquals(new TerminalSize(80, 22), messages.getSize(),
            "a hidden spinner contributes no row, so the transcript fills the full height");
        assertEquals(new TerminalPosition(0, 22), input.getPosition());
    }

    @Test
    void visibleSpinnerKeepsItsPinnedRowDuringToolUse() {
        // 改动 B: while the spinner is on-screen (kept alive by tool-use mode), it keeps
        // contributing rows to the bottom pinned zone, so the verb stays visible without
        // the user scrolling. It must NOT fully collapse (yield ALL its rows back to the
        // transcript) mid-execution. This drives a REAL SpinnerComponent, not a stub.
        SmartLayout layout = new SmartLayout();
        CountingComponent messages = new CountingComponent(10, 3);
        SpinnerComponent spinner = new SpinnerComponent();
        CountingComponent input = new CountingComponent(10, 2);
        spinner.setVisible(true);

        layout.doLayout(new TerminalSize(80, 24), List.of(messages, spinner, input));

        TerminalPosition spinnerPos = spinner.getPosition();
        TerminalSize spinnerSize = spinner.getSize();
        assertTrue(spinnerSize.getRows() >= 1, "an on-screen spinner occupies at least one row");
        assertTrue(messages.getSize().getRows() < 24,
            "transcript is shorter than full height, so the spinner visibly holds rows");

        // Structural stack: transcript top → spinner → input (each next row ≥ previous).
        int messageRow = messages.getPosition().getRow();
        assertTrue(spinnerPos.getRow() >= messageRow,
            "spinner is pinned below the transcript, not scrolled out of view");
        assertTrue(input.getPosition().getRow() >= spinnerPos.getRow() + spinnerSize.getRows(),
            "input stacks below the spinner in the same pinned zone");
    }

    private static class CountingComponent extends AbstractComponent<CountingComponent> {
        private final TerminalSize preferredSize;
        int preferredSizeCalls;

        CountingComponent(int columns, int rows) {
            preferredSize = new TerminalSize(columns, rows);
        }

        @Override
        protected TerminalSize calculatePreferredSize() {
            preferredSizeCalls++;
            return preferredSize;
        }

        @Override
        protected ComponentRenderer<CountingComponent> createDefaultRenderer() {
            return new ComponentRenderer<>() {
                @Override
                public TerminalSize getPreferredSize(CountingComponent component) {
                    return preferredSize;
                }

                @Override
                public void drawComponent(TextGUIGraphics graphics, CountingComponent component) {}
            };
        }
    }

    private static class CountingOverlay extends CountingComponent implements InlineOverlay {
        boolean active;

        CountingOverlay(int columns, int rows) {
            super(columns, rows);
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void handleKey(KeyStroke key, AtomicBoolean deliver) {}
    }

    private static final class CountingFlowOverlay extends CountingOverlay {
        CountingFlowOverlay(int columns, int rows) { super(columns, rows); }

        @Override public boolean overlaysTranscript() { return false; }
    }
}
