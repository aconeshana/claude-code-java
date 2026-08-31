package com.claudecode.ui.lanterna.components;

import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.LayoutManager;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stacked vertical layout that pins the input/status zone to the BOTTOM of the terminal while
 * giving all remaining vertical space to the messagePanel.
 */
public class SmartLayout implements LayoutManager {

    private List<Component> lastComponents;
    private final Map<Component, Boolean> lastOverlayStates = new IdentityHashMap<>();

    @Override
    public TerminalSize getPreferredSize(List<Component> components) {
        int w = 0, h = 0;
        for (Component c : components) {
            if (!participates(c)) continue;
            TerminalSize ps = c.getPreferredSize();
            w = Math.max(w, ps.getColumns());
            h += ps.getRows();
        }
        return new TerminalSize(w, h);
    }

    @Override
    public void doLayout(TerminalSize area, List<Component> components) {
        if (components.isEmpty()) {
            rememberState(components);
            return;
        }

        for (Component component : components) {
            if (component instanceof InlineOverlay overlay) {
                component.setVisible(overlay.isVisibleInScene());
                if (!overlay.isVisibleInScene()) {
                    component.setPosition(TerminalPosition.of(0, 0));
                    component.setSize(TerminalSize.of(0, 0));
                }
            }
        }

        int width  = area.getColumns();
        int height = area.getRows();
        if (width <= 0 || height <= 0) {
            rememberState(components);
            return;
        }

        // Sum the persistent pinned zone. Active overlays are intentionally
        // excluded: they cover the bottom of the transcript instead of moving
        // every transcript row, input divider, and footer on open/close.
        int pinnedH = 0;
        TerminalSize[] preferredSizes = new TerminalSize[components.size()];
        for (int i = 1; i < components.size(); i++) {
            Component component = components.get(i);
            if (!participates(component)) continue;
            TerminalSize preferredSize = component.getPreferredSize();
            preferredSizes[i] = preferredSize;
            if (!(component instanceof InlineOverlay overlay)
                    || !overlay.overlaysTranscript()) {
                pinnedH += preferredSize.getRows();
            }
        }

        // messagePanel always fills ALL remaining space above the pinned zone.
        // This keeps the input / status bar anchored to the bottom of the terminal
        // regardless of how few content lines are present (no gap between content
        // and input when the conversation is short).
        Component messagePanel = components.getFirst();
        int msgH = Math.max(1, height - pinnedH);

        // Layout messagePanel at top
        messagePanel.setPosition(new TerminalPosition(0, 0));
        messagePanel.setSize(new TerminalSize(width, msgH));

        // Stack persistent components at the bottom, immediately below the
        // unchanged transcript viewport.
        int y = msgH;
        for (int i = 1; i < components.size(); i++) {
            Component c = components.get(i);
            TerminalSize preferredSize = preferredSizes[i];
            if (preferredSize == null || isCoveringOverlay(c)) continue;
            int h = preferredSize.getRows();
            if (y + h > height) h = Math.max(0, height - y);
            c.setPosition(new TerminalPosition(0, y));
            c.setSize(new TerminalSize(width, h));
            y += h;
        }

        // Mount the one active picker last in component order, immediately
        // above the persistent footer. Lanterna's container draw order paints
        // it over the transcript rows beneath it.
        for (int i = 1; i < components.size(); i++) {
            Component c = components.get(i);
            TerminalSize preferredSize = preferredSizes[i];
            if (preferredSize == null || !isCoveringOverlay(c)) continue;
            int h = Math.min(preferredSize.getRows(), msgH);
            c.setPosition(new TerminalPosition(0, msgH - h));
            c.setSize(new TerminalSize(width, h));
        }
        rememberState(components);
    }

    @Override
    public boolean hasChanged() {
        if (lastComponents == null) return true;
        for (Component component : lastComponents) {
            if (component instanceof InlineOverlay overlay
                    && !Boolean.valueOf(overlay.isVisibleInScene()).equals(lastOverlayStates.get(component))) {
                return true;
            }
        }
        return false;
    }

    private static boolean participates(Component component) {
        return !(component instanceof InlineOverlay overlay) || overlay.isVisibleInScene();
    }

    private static boolean isCoveringOverlay(Component component) {
        return component instanceof InlineOverlay overlay && overlay.overlaysTranscript();
    }

    private void rememberState(List<Component> components) {
        lastComponents = List.copyOf(components);
        lastOverlayStates.clear();
        for (Component component : components) {
            if (component instanceof InlineOverlay overlay) {
                lastOverlayStates.put(component, overlay.isVisibleInScene());
            }
        }
    }
}
