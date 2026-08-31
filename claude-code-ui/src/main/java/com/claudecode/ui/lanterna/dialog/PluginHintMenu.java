package com.claudecode.ui.lanterna.dialog;

import com.claudecode.tools.hints.ClaudeCodeHint;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.TextGUIGraphics;

import java.util.function.BiConsumer;


public final class PluginHintMenu extends TimedChoiceOverlay<PluginHintMenu.Response> {

    /** The user's response to the plugin hint. */
    public enum Response {
        /** Install the suggested plugin. */
        INSTALL,
        /** Dismiss without installing (may reappear next session). */
        NOT_NOW,
        /** Suppress all plugin hints for the rest of the session. */
        DONT_ASK_AGAIN
    }

    /** The three options, in focus order. */
    private enum Option {
        INSTALL(Response.INSTALL, "Install",
                "Install and enable the suggested plugin"),
        NOT_NOW(Response.NOT_NOW, "Not now",
                "Dismiss this prompt (may reappear in a later session)"),
        DONT_ASK_AGAIN(Response.DONT_ASK_AGAIN, "Don't ask again",
                "Suppress plugin hints for the rest of this session");

        final Response response;
        final String label;
        final String description;

        Option(Response response, String label, String description) {
            this.response = response;
            this.label = label;
            this.description = description;
        }
    }

    private static final Option[] OPTIONS = Option.values();

    private ClaudeCodeHint hint;

    private final Body body = new Body();

    public PluginHintMenu() {
        super(30_000L, "plugin-hint-timeout");
        addComponent(body);
    }

    /** Activates the prompt and starts the inactivity timer. Runs on the GUI thread. */
    public void show(ClaudeCodeHint hint,
                     BiConsumer<Response, Boolean> consumer,
                     Runnable onClose,
                     MultiWindowTextGUI gui) {
        this.hint = hint;
        activate(consumer, onClose, gui);
    }

    // ── TimedChoiceOverlay hooks ─────────────────────────────────────────────

    @Override
    protected int optionCount() {
        return OPTIONS.length;
    }

    @Override
    protected Response responseAt(int index) {
        return OPTIONS[index].response;
    }

    @Override
    protected Response notNowResponse() {
        return Response.NOT_NOW;
    }

    @Override
    protected void clearPayload() {
        this.hint = null;
    }

    // ── rendering ────────────────────────────────────────────────────────────

    private final class Body extends AbstractComponent<Body> {
        @Override
        protected ComponentRenderer<Body> createDefaultRenderer() {
            return new ComponentRenderer<>() {
                @Override
                public TerminalSize getPreferredSize(Body c) {
                    if (!active || hint == null) {
                        return TerminalSize.of(0, 0);
                    }
                    return new TerminalSize(80, 3 + OPTIONS.length + 1);
                }

                @Override
                public void drawComponent(TextGUIGraphics g, Body c) {
                    if (!active || hint == null) {
                        return;
                    }
                    g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                    g.fill(' ');
                    int width = g.getSize().getColumns();
                    int y = 0;

                    // Title line — the suggested plugin slug.
                    g.setForegroundColor(LanternaTheme.claude());
                    g.putString(1, y, InlineOverlay.clip("◆ Plugin suggested: " + hint.value(), width - 2));
                    y++;

                    // Description — which command emitted the hint.
                    g.setForegroundColor(LanternaTheme.divider());
                    String desc = "Command `" + hint.sourceCommand() + "` suggests installing this plugin.";
                    g.putString(1, y, InlineOverlay.clip(desc, width - 2));
                    y++;
                    y++; // blank

                    // Options with a focus pointer.
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
                    g.putString(1, y, InlineOverlay.clip(
                            "↑/↓ to move · enter to select · 1-3 to pick · esc to dismiss", width - 2));
                }
            };
        }
    }
}
