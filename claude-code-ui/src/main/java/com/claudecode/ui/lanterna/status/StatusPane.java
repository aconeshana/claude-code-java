package com.claudecode.ui.lanterna.status;


import com.claudecode.commands.StatusProperty;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;

import java.util.List;
import com.claudecode.ui.lanterna.features.settings.SettingsTabContainer;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Read-only "Status" tab body for {@link SettingsTabContainer}.
 */
public final class StatusPane extends AbstractComponent<StatusPane> {

    private static final int LEFT_PAD = 2;
    private static final int VALUE_COL = 24;

    private boolean active;
    private List<StatusProperty> properties = List.of();
    private List<String> diagnostics = List.of();

    /** Activates the pane with a freshly computed property list. Must run on the GUI thread. */
    public void show(List<StatusProperty> properties) {
        this.properties = properties;
        this.active = true;
        invalidate();
    }

    /** Deactivates the pane (collapses to zero height). */
    public void hide() {
        this.active = false;
        this.diagnostics = List.of();
        invalidate();
    }

    public void setDiagnostics(List<String> diagnostics) {
        this.diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
        invalidate();
    }

    List<String> diagnostics() { return diagnostics; }

    public boolean isShowing() { return active; }

    @Override
    protected ComponentRenderer<StatusPane> createDefaultRenderer() {
        return new Renderer();
    }

    private final class Renderer implements ComponentRenderer<StatusPane> {

        @Override
        public TerminalSize getPreferredSize(StatusPane c) {
            if (!active) return new TerminalSize(0, 0);
            int diagnosticRows = diagnostics.isEmpty() ? 0 : diagnostics.size() + 3;
            return new TerminalSize(Math.max(60, LEFT_PAD * 2 + VALUE_COL + 20),
                properties.size() + 2 + diagnosticRows);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, StatusPane c) {
            if (!active) return;
            g.fill(' ');

            // Row 0: divider (matches ConfigPanel's row 0, so tab bodies align)
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, g.getSize().getColumns())));

            int row = 1;
            for (StatusProperty p : properties) {
                int valueColumn = LEFT_PAD;
                if (p.label() != null) {
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.enableModifiers(SGR.BOLD);
                    g.putString(LEFT_PAD, row, p.label() + ":");
                    g.disableModifiers(SGR.BOLD);
                    valueColumn = LEFT_PAD + VALUE_COL;
                }
                g.setForegroundColor(LanternaTheme.inputText());
                g.putString(valueColumn, row, p.value());
                row++;
            }

            if (!diagnostics.isEmpty()) {
                row++;
                g.setForegroundColor(LanternaTheme.inputText());
                g.enableModifiers(SGR.BOLD);
                g.putString(LEFT_PAD, row++, "System Diagnostics");
                g.disableModifiers(SGR.BOLD);
                for (String diagnostic : diagnostics) {
                    g.setForegroundColor(LanternaTheme.toolError());
                    g.putString(LEFT_PAD + 1, row, "⚠");
                    g.setForegroundColor(LanternaTheme.inputText());
                    g.putString(LEFT_PAD + 3, row, diagnostic);
                    row++;
                }
            }
        }
    }
}
