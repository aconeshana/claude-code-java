package com.claudecode.ui.lanterna.status;

import com.claudecode.commands.impl.info.CostCommand;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.claudecode.ui.lanterna.features.settings.SettingsTabContainer;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Read-only "Usage" tab body for {@link SettingsTabContainer}.
 */
public final class UsagePane extends AbstractComponent<UsagePane> {

    private static final int LEFT_PAD = 2;
    private boolean active;

    /** Activates the pane. Must run on the GUI thread. */
    public void show() {
        this.active = true;
        invalidate();
    }

    /** Deactivates the pane (collapses to zero height). */
    public void hide() {
        this.active = false;
        invalidate();
    }

    public boolean isShowing() { return active; }

    @Override
    protected ComponentRenderer<UsagePane> createDefaultRenderer() {
        return new Renderer();
    }

    private final class Renderer implements ComponentRenderer<UsagePane> {

        @Override
        public TerminalSize getPreferredSize(UsagePane c) {
            if (!active) return new TerminalSize(0, 0);
            return new TerminalSize(80, sessionLines().length + 4);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, UsagePane c) {
            if (!active) return;
            g.fill(' ');

            String[] lines = sessionLines();
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 0, "Session");
            int row = 2;
            for (String line : lines) {
                g.putString(LEFT_PAD, row, line);
                row++;
            }
            g.putString(LEFT_PAD, row + 1, "Esc to cancel");
        }

        private String[] sessionLines() {
            return CostCommand.sessionSummary().split("\n");
        }
    }
}
