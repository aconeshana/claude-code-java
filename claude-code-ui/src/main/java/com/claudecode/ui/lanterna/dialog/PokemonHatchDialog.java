package com.claudecode.ui.lanterna.dialog;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.pokemon.PokemonEvolution;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Inline confirmation before replacing the persisted welcome Pokémon.
 */
@Explanation("Protects Pokémon progress behind an explicit hatch confirmation.")
public final class PokemonHatchDialog extends Panel implements InlineOverlay {

    private final Body body = new Body();
    private boolean active;
    private int selected = 1;
    private CommandContext.PokemonHatchRequest request;
    private Consumer<CommandResult> onDone;
    private Consumer<Runnable> guiInvoker;
    private long actionGeneration;
    private boolean actionInFlight;

    public PokemonHatchDialog() {
        addComponent(body);
    }

    public void show(CommandContext.PokemonHatchRequest request,
                     Consumer<CommandResult> onDone) {
        actionGeneration++;
        this.request = request;
        this.onDone = onDone;
        this.selected = 1;
        this.actionInFlight = false;
        this.active = request != null;
        invalidate();
    }

    public void setGuiInvoker(Consumer<Runnable> guiInvoker) {
        this.guiInvoker = guiInvoker;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        if (actionInFlight) {
            deliver.set(false);
            return;
        }
        switch (key.getKeyType()) {
            case ARROW_UP, ARROW_DOWN -> selected = 1 - selected;
            case ENTER -> resolve(selected == 0);
            case ESCAPE -> resolve(false);
            case CHARACTER -> {
                Character ch = key.getCharacter();
                if (ch != null && (ch == '1' || ch == 'y' || ch == 'Y')) resolve(true);
                else if (ch != null && (ch == '2' || ch == 'n' || ch == 'N')) resolve(false);
            }
            default -> { }
        }
        deliver.set(false);
        invalidate();
    }

    private void resolve(boolean hatch) {
        if (!active || request == null || onDone == null) return;
        CommandContext.PokemonHatchRequest current = request;
        Consumer<CommandResult> callback = onDone;
        if (guiInvoker == null) {
            finishAction(++actionGeneration, callback, runAction(current, hatch));
            return;
        }
        actionInFlight = true;
        long generation = ++actionGeneration;
        invalidate();
        Thread.ofVirtual().name("pokemon-hatch").start(() -> {
            CommandResult result = runAction(current, hatch);
            guiInvoker.accept(() -> finishAction(generation, callback, result));
        });
    }

    private CommandResult runAction(CommandContext.PokemonHatchRequest current, boolean hatch) {
        try {
            return (hatch ? current.confirm() : current.cancel()).get();
        } catch (Exception failure) {
            return CommandResult.of("Failed to hatch Pokémon: " + failure.getMessage());
        }
    }

    private void finishAction(long generation, Consumer<CommandResult> callback,
                              CommandResult result) {
        if (generation != actionGeneration) return;
        actionInFlight = false;
        active = false;
        request = null;
        onDone = null;
        invalidate();
        callback.accept(result);
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        return active ? super.calculatePreferredSize() : TerminalSize.of(0, 0);
    }

    private final class Body extends AbstractComponent<Body> {
        @Override
        protected ComponentRenderer<Body> createDefaultRenderer() {
            return new ComponentRenderer<>() {
                @Override
                public TerminalSize getPreferredSize(Body component) {
                    return active ? new TerminalSize(80, 9) : TerminalSize.of(0, 0);
                }

                @Override
                public void drawComponent(TextGUIGraphics g, Body component) {
                    if (!active || request == null) return;
                    g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                    g.fill(' ');
                    int width = g.getSize().getColumns();
                    var current = request.current();
                    int level = current == null ? 1 : PokemonEvolution.progress(current).level();
                    String name = current == null ? "current Pokémon" : current.displayName();

                    g.setForegroundColor(LanternaTheme.permission());
                    g.putString(1, 0, InlineOverlay.clip("Hatch a new Pokémon?", width - 2));
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(1, 1, InlineOverlay.clip(
                        "Current: " + name + " · Lv " + level, width - 2));
                    g.setForegroundColor(LanternaTheme.inputText());
                    g.putString(1, 3, InlineOverlay.clip(
                        "This replaces your current Pokémon and resets its level,", width - 2));
                    g.putString(1, 4, InlineOverlay.clip(
                        "experience, evolution progress, rarity, and stats.", width - 2));
                    drawOption(g, width, 6, 0, "Yes, hatch a new Pokémon");
                    drawOption(g, width, 7, 1, "No, keep current Pokémon");
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(1, 8, InlineOverlay.clip(
                        actionInFlight ? "Hatching Pokémon…"
                            : "↑/↓ to select · Enter to confirm · Esc to cancel", width - 2));
                }

                private void drawOption(TextGUIGraphics g, int width, int row,
                                        int index, String label) {
                    boolean focused = selected == index;
                    g.setForegroundColor(focused
                        ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                    g.putString(1, row, InlineOverlay.clip(
                        (focused ? " ❯ " : "   ") + (index + 1) + ". " + label,
                        width - 2));
                }
            };
        }
    }
}
