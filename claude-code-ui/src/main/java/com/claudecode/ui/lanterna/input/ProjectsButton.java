package com.claudecode.ui.lanterna.input;

import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.gui2.Label;

/**
 * The {@code ≡} project-drawer button — leftmost footer stop.
 *
 * <p>Three visual states: keyboard-selected or mouse-hovered (REVERSE),
 * drawer-open (accent), idle (dim). Selection and activation are driven by
 * {@link PromptFooter}; this class only owns the state and its projection.
 *
 * <ul>
 *   <li>Java-side extension with no 2.1.197 counterpart.</li>
 * </ul>
 */
final class ProjectsButton {

    private final Label label = new Label("≡ ");
    private final FooterMouseLatch latch = new FooterMouseLatch();
    private boolean selected;
    private boolean active;

    ProjectsButton() {
        label.setForegroundColor(LanternaTheme.welcomeDim());
    }

    Label component() { return label; }

    FooterMouseLatch latch() { return latch; }

    boolean isSelected() { return selected; }

    void setSelected(boolean selected) { this.selected = selected; }

    boolean isActive() { return active; }

    /** @return true when the drawer-open state actually changed */
    boolean setActive(boolean active) {
        if (this.active == active) return false;
        this.active = active;
        return true;
    }

    void render() {
        label.setForegroundColor(active
            ? LanternaTheme.suggestion() : LanternaTheme.welcomeDim());
        if (selected || latch.hovered()) {
            label.addStyle(SGR.REVERSE);
        } else {
            label.removeStyle(SGR.REVERSE);
        }
    }
}
