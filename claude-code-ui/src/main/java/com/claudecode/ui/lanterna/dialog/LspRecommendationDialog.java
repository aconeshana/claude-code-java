package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.lsp.LspPluginRecommendation;
import com.claudecode.core.lsp.LspRecommendationResponse;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.TextGUIGraphics;

import java.util.function.BiConsumer;

/**
 * Inline, non-blocking LSP-plugin recommendation prompt shown when the user opens a file whose
 * language has no active LSP server but a marketplace plugin (whose server binary is already
 * installed) could provide one.
 */
public final class LspRecommendationDialog extends TimedChoiceOverlay<LspRecommendationResponse> {

    /** The four responses, in focus order. */
    private enum Option {
        INSTALL(LspRecommendationResponse.YES, "Install",
                "Install and enable the LSP plugin, then re-read LSP config"),
        NOT_NOW(LspRecommendationResponse.NO, "Not now",
                "Dismiss this prompt (ignored only if it auto-closes on timeout)"),
        NEVER(LspRecommendationResponse.NEVER, "Never suggest for this plugin",
                "Add this plugin to the never-suggest list"),
        DISABLE(LspRecommendationResponse.DISABLE, "Disable recommendations",
                "Turn off all LSP plugin recommendations globally");

        final LspRecommendationResponse response;
        final String label;
        final String description;

        Option(LspRecommendationResponse response, String label, String description) {
            this.response = response;
            this.label = label;
            this.description = description;
        }
    }

    private static final Option[] OPTIONS = Option.values();

    private LspPluginRecommendation rec;

    private final Body body = new Body();

    public LspRecommendationDialog() {
        super(30_000L, "lsp-recommendation-timeout");
        addComponent(body);
    }

    // ── entry point (called on the GUI thread by the screen) ────────────────

    /** Activates the prompt and starts the inactivity timer. Runs on the GUI thread. */
    public void show(LspPluginRecommendation rec,
                     BiConsumer<LspRecommendationResponse, Boolean> consumer,
                     Runnable onClose,
                     MultiWindowTextGUI gui) {
        this.rec = rec;
        activate(consumer, onClose, gui);
    }

    // ── TimedChoiceOverlay hooks ─────────────────────────────────────────────

    @Override
    protected int optionCount() {
        return OPTIONS.length;
    }

    @Override
    protected LspRecommendationResponse responseAt(int index) {
        return OPTIONS[index].response;
    }

    @Override
    protected LspRecommendationResponse notNowResponse() {
        return LspRecommendationResponse.NO;
    }

    @Override
    protected void clearPayload() {
        this.rec = null;
    }

    // ── rendering ────────────────────────────────────────────────────────────

    private final class Body extends AbstractComponent<Body> {
        @Override
        protected ComponentRenderer<Body> createDefaultRenderer() {
            return new ComponentRenderer<>() {
                @Override
                public TerminalSize getPreferredSize(Body c) {
                    if (!active || rec == null) {
                        return TerminalSize.of(0, 0);
                    }
                    // title + description + extension line + blank + 4 options + hint
                    return new TerminalSize(80, 4 + OPTIONS.length + 1);
                }

                @Override
                public void drawComponent(TextGUIGraphics g, Body c) {
                    if (!active || rec == null) {
                        return;
                    }
                    g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                    g.fill(' ');
                    int width = g.getSize().getColumns();
                    int y = 0;

                    // Title line — plugin name + official badge.
                    g.setForegroundColor(LanternaTheme.claude());
                    String title = rec.pluginName();
                    if (rec.isOfficial()) {
                        title += "  (official)";
                    }
                    g.putString(1, y, InlineOverlay.clip("◆ " + title, width - 2));
                    y++;

                    // Description.
                    g.setForegroundColor(LanternaTheme.divider());
                    g.putString(1, y, InlineOverlay.clip(rec.description(), width - 2));
                    y++;

                    // Covered extensions + launch command hint.
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    String ext = String.join(", ", rec.extensions());
                    g.putString(1, y, InlineOverlay.clip("LSP available for " + ext + "  ·  " + rec.command(),
                            width - 2));
                    y++;
                    y++; // blank

                    // Four options with a focus pointer.
                    for (int i = 0; i < OPTIONS.length; i++) {
                        Option opt = OPTIONS[i];
                        boolean focused = focus == i;
                        String prefix = focused ? " ❯ " : "   ";
                        g.setForegroundColor(focused ? LanternaTheme.suggestion()
                                : LanternaTheme.inputText());
                        g.putString(1, y, InlineOverlay.clip(prefix + (i + 1) + ". " + opt.label, width - 2));
                        y++;
                        g.setForegroundColor(LanternaTheme.welcomeDim());
                        g.putString(1, y, InlineOverlay.clip("       " + opt.description, width - 2));
                        y++;
                    }

                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(1, y, InlineOverlay.clip("↑/↓ to move · enter to select · 1-4 to pick · esc to dismiss",
                            width - 2));
                }
            };
        }
    }
}
