package com.claudecode.ui.lanterna.input;

import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import org.apache.commons.lang3.StringUtils;

/**
 * The horizontal rules framing the prompt: a top divider that can carry a
 * badge, and a plain bottom divider.
 *
 * <p>Both rules share one border color: the bash border while in bash mode,
 * otherwise the {@code /color} session color, otherwise the default prompt
 * border. Two badges compete for the top rule — the {@code /rename} session
 * name (painted inverse in the banner color, right-aligned) and the history
 * navigation label (dim, left-aligned); the session name wins.
 *
 * <ul>
 *   <li>{@code src/components/PromptInput/PromptInput.tsx} — the
 *       {@code borderColor} rule ({@code bashBorder} / session color /
 *       {@code promptBorder}) and the top/bottom border box.</li>
 *   <li>{@code src/components/PromptInput/PromptInputBorder.tsx} — the
 *       right-aligned session-name badge on the top border.</li>
 * </ul>
 */
final class PromptDividers {

    /** Row-panel: left dashes + optional colored badge + trailing dashes. */
    private final Panel topRow = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(0));
    private final Label topLeft = new Label("");
    private final Label topBadge = new Label("");
    private final Label topTrail = new Label("");
    private final Label bottom = new Label("");

    private int width = 80;
    private boolean bashMode;
    /** User-set prompt-bar color from {@code /color}; null = default. */
    private TextColor sessionColor;
    /** Session name set by {@code /rename}; null = no banner badge. */
    private String sessionName;
    /** History navigation label ({@code ↑ 3/12}); shown only without a session banner. */
    private String historyLabel;

    PromptDividers() {
        topLeft.setForegroundColor(LanternaTheme.divider());
        bottom.setForegroundColor(LanternaTheme.divider());
        topRow.addComponent(topLeft);
        topRow.addComponent(topBadge);
        topRow.addComponent(topTrail);
    }

    Panel top() { return topRow; }

    Label bottom() { return bottom; }

    int width() { return width; }

    void setWidth(int width) {
        this.width = width;
        bottom.setText("─".repeat(Math.max(1, width)));
        render();
    }

    void setBashMode(boolean bashMode) {
        if (this.bashMode == bashMode) return;
        this.bashMode = bashMode;
        render();
    }

    /** Applies a {@code /color} name; null, {@code default} or an unknown name restores the default. */
    void setSessionColor(String colorName) {
        sessionColor = colorName == null || "default".equals(colorName)
            ? null : LanternaTheme.agentColor(colorName);
        render();
    }

    void setSessionName(String name) {
        sessionName = StringUtils.isNotBlank(name) ? name : null;
        render();
    }

    void setHistoryLabel(String label) {
        historyLabel = label;
        render();
    }

    private void render() {
        if (sessionName == null) {
            renderPlain();
        } else {
            renderBanner();
        }
    }

    private void renderPlain() {
        TextColor borderColor = bashMode
            ? LanternaTheme.bashBorder()
            : sessionColor != null ? sessionColor : LanternaTheme.promptBorder();
        if (StringUtils.isNotBlank(historyLabel)) {
            String badge = " " + historyLabel + " ";
            int left = Math.min(2, Math.max(0, width - badge.length()));
            int trailing = Math.max(0, width - left - badge.length());
            topLeft.setText("─".repeat(left));
            topBadge.setText(badge);
            topBadge.setForegroundColor(LanternaTheme.welcomeDim());
            topBadge.setBackgroundColor(TextColor.ANSI.DEFAULT);
            topTrail.setText("─".repeat(trailing));
            topTrail.setForegroundColor(borderColor);
            topTrail.setBackgroundColor(TextColor.ANSI.DEFAULT);
        } else {
            topLeft.setText("─".repeat(Math.max(1, width)));
            topBadge.setText("");
            topBadge.setForegroundColor(TextColor.ANSI.DEFAULT);
            topBadge.setBackgroundColor(TextColor.ANSI.DEFAULT);
            topTrail.setText("");
            topTrail.setForegroundColor(TextColor.ANSI.DEFAULT);
            topTrail.setBackgroundColor(TextColor.ANSI.DEFAULT);
        }
        topLeft.setForegroundColor(borderColor);
        // The bottom rule follows the same color, otherwise /color pink would
        // flip only the top border and leave the bottom one gray.
        bottom.setForegroundColor(borderColor);
        bottom.setBackgroundColor(TextColor.ANSI.DEFAULT);
    }

    private void renderBanner() {
        TextColor bannerColor = sessionColor != null ? sessionColor : LanternaTheme.agentCyan();
        // badge = " NAME " (space-padded); trail = "──" without background
        String badgeText = " " + sessionName + " ";
        String trail = "──";
        int dashes = Math.max(0, width - badgeText.length() - trail.length());
        topLeft.setText(dashes > 0 ? "─".repeat(dashes) : "");
        topLeft.setForegroundColor(bannerColor);
        topLeft.setBackgroundColor(TextColor.ANSI.DEFAULT);
        topBadge.setText(badgeText);
        topBadge.setForegroundColor(LanternaTheme.inverseText());
        topBadge.setBackgroundColor(bannerColor);
        topTrail.setText(trail);
        topTrail.setForegroundColor(bannerColor);
        topTrail.setBackgroundColor(TextColor.ANSI.DEFAULT);
        bottom.setForegroundColor(bannerColor);
        bottom.setBackgroundColor(TextColor.ANSI.DEFAULT);
    }
}
