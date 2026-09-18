package com.claudecode.ui.lanterna.input;

import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;

/**
 * Background-tasks pill group in the prompt footer: the single summary pill
 * ({@code "1 shell"}), its optional attention hint, or — while teammates are
 * running — the scrolling {@code @name} multi-agent strip.
 *
 * <p>Pure projection of {@link PromptTaskNavigationController}: selection and
 * key handling live in the controller, mouse latching in {@link PromptFooter}.
 *
 * <ul>
 *   <li>{@code src/components/PromptInput/PromptInputFooterLeftSide.tsx} — the
 *       {@code tasksPart} pill and its trailing hint.</li>
 *   <li>{@code src/components/PromptInput/PromptInputFooter.tsx} — the teammate
 *       strip ({@code ← @a @b → · shift + ↓ expand}) shown when a swarm is active.</li>
 * </ul>
 */
final class TasksPill {

    /** Summary pill text; cyan accent, REVERSE while selected. Empty = hidden. */
    private final Label pillLabel = new Label("");
    /** Dynamic multi-agent pill row; the summary pill is inserted here when needed. */
    private final Panel pillsPanel =
        new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(0));
    /** Optional attention CTA next to the ordinary task pill. */
    private final Label hintLabel = new Label("");
    private final FooterMouseLatch latch = new FooterMouseLatch();

    Panel pillsPanel() { return pillsPanel; }

    Label hintLabel() { return hintLabel; }

    FooterMouseLatch latch() { return latch; }

    String pillText() { return pillLabel.getText(); }

    String hintText() { return hintLabel.getText(); }

    /** Repaints from the controller's current projection. */
    void render(PromptTaskNavigationController navigation, int footerWidth) {
        if (navigation.isTeammateFooterVisible()) {
            renderTeammateStrip(navigation, footerWidth);
            setTextIfChanged(hintLabel, "");
            return;
        }
        pillsPanel.removeAllComponents();
        pillsPanel.addComponent(pillLabel);
        PromptTaskNavigationController.PillView pill = navigation.pillView();
        if (pill.label().isEmpty()) {
            pillLabel.removeStyle(SGR.REVERSE);
            setTextIfChanged(pillLabel, "");
            setTextIfChanged(hintLabel, "");
            return;
        }
        pillLabel.setForegroundColor(LanternaTheme.backgroundTaskAccent());
        if (pill.selected() || latch.hovered()) pillLabel.addStyle(SGR.REVERSE);
        else pillLabel.removeStyle(SGR.REVERSE);
        setTextIfChanged(pillLabel, pill.label());
        hintLabel.setForegroundColor(LanternaTheme.welcomeDim());
        setTextIfChanged(hintLabel, pill.hint());
    }

    /** Renders the original multi-agent footer projection using the shared width window. */
    private void renderTeammateStrip(PromptTaskNavigationController navigation, int footerWidth) {
        PromptTaskNavigationController.TeammateFooterView footer =
            navigation.teammateFooterView(Math.max(20, footerWidth - 24));
        pillsPanel.removeAllComponents();
        if (footer.showLeftArrow()) {
            Label left = new Label("← ");
            left.setForegroundColor(LanternaTheme.welcomeDim());
            pillsPanel.addComponent(left);
        }
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < footer.visiblePills().size(); i++) {
            PromptTaskNavigationController.TeammatePillView pill = footer.visiblePills().get(i);
            if (i > 0) {
                pillsPanel.addComponent(new Label(" "));
                plain.append(' ');
            }
            String text = "@" + pill.name();
            Label label = new Label(text);
            label.setForegroundColor(pill.idle()
                ? LanternaTheme.welcomeDim() : LanternaTheme.inputText());
            if (pill.viewed()) label.addStyle(SGR.BOLD);
            if (pill.selected()) label.addStyle(SGR.REVERSE);
            pillsPanel.addComponent(label);
            plain.append(text);
        }
        if (footer.showRightArrow()) {
            Label right = new Label(" →");
            right.setForegroundColor(LanternaTheme.welcomeDim());
            pillsPanel.addComponent(right);
        }
        Label expandHint = new Label(" · shift + ↓ expand");
        expandHint.setForegroundColor(LanternaTheme.welcomeDim());
        pillsPanel.addComponent(expandHint);
        plain.append(" · shift + ↓ expand");
        // Keep the plain-text projection meaningful for both footer shapes.
        setTextIfChanged(pillLabel, plain.toString());
    }

    static void setTextIfChanged(Label label, String text) {
        if (!text.equals(label.getText())) {
            label.setText(text);
        }
    }
}
