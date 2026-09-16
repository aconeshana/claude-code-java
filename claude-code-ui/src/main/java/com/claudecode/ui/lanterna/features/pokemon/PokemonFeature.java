package com.claudecode.ui.lanterna.features.pokemon;

import com.claudecode.commands.CommandContext;
import com.claudecode.core.pokemon.PokemonEvolution;
import com.claudecode.core.pokemon.PokemonProfile;
import com.claudecode.ui.lanterna.components.LogoPanel;
import com.claudecode.ui.lanterna.components.PokemonCardRenderer;
import com.claudecode.ui.lanterna.components.PokemonEvolutionOverlay;
import com.claudecode.ui.lanterna.components.WelcomeBlockHolder;
import com.claudecode.ui.lanterna.dialog.PokemonHatchDialog;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.features.ReplFeature;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import java.util.List;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.screen.Screen;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the welcome-banner Pokémon buddy: hatch dialog, detail-card rendering, and
 * experience/evolution bookkeeping. Extracted from {@code LanternaReplScreen}, which still owns
 * the {@link WelcomeBlockHolder} shared with the model-line and web-gateway-line writers.
 */
public final class PokemonFeature implements ReplCommandUiBridge.Pokemon, ReplFeature {

    private static final Logger log = LoggerFactory.getLogger(PokemonFeature.class);

    private record ProgressUpdate(PokemonProfile before, PokemonProfile after) {}

    private final MultiWindowTextGUI gui;
    private final Screen screen;
    private final MessagePanel messagePanel;
    private final InputPanel inputPanel;
    private final LogoPanel welcomePanel;
    private final WelcomeBlockHolder welcomeBlock;
    private final Supplier<String> model;
    private final ReplTranscriptSink sink;

    private final PokemonCardRenderer cardRenderer = new PokemonCardRenderer();
    private final PokemonHatchDialog hatchDialog = new PokemonHatchDialog();
    private final Object experienceLock = new Object();
    private PokemonProfile experienceState;

    public PokemonFeature(
            MultiWindowTextGUI gui,
            Screen screen,
            MessagePanel messagePanel,
            InputPanel inputPanel,
            LogoPanel welcomePanel,
            WelcomeBlockHolder welcomeBlock,
            Supplier<String> model,
            ReplTranscriptSink sink) {
        this.gui = gui;
        this.screen = screen;
        this.messagePanel = messagePanel;
        this.inputPanel = inputPanel;
        this.welcomePanel = welcomePanel;
        this.welcomeBlock = welcomeBlock;
        this.model = model;
        this.sink = sink;
        this.experienceState = welcomePanel.pokemon();
        if (gui != null) {
            hatchDialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
        }
    }

    @Override public List<InlineOverlay> overlays() {
        return List.of(hatchDialog);
    }

    public Component view() {
        return hatchDialog;
    }

    @Override
    public void openHatchDialog(CommandContext.PokemonHatchRequest request) {
        if (gui == null || request == null) return;
        gui.getGUIThread().invokeLater(() -> hatchDialog.show(request, result -> {
            if (result != null && result.output() != null && !StringUtils.isBlank(result.output())) {
                sink.system(result.output());
            }
            inputPanel.takeFocus();
        }));
    }

    /** Live-applies a newly hatched Pokémon and renders its Buddy-style detail card. */
    @Override
    public void setWelcomePokemon(PokemonProfile pokemon) {
        synchronized (experienceLock) {
            experienceState = pokemon;
        }
        Runnable repaint = () -> {
            if (messagePanel == null) return;
            int terminalWidth = terminalWidth();
            LogoPanel.WelcomeBlock block = welcomeBlock.get();
            if (block != null) {
                welcomeBlock.set(welcomePanel.replacePokemon(
                    messagePanel, block, terminalWidth, model.get(), pokemon));
            }
            cardRenderer.show(messagePanel, terminalWidth, pokemon);
            try { if (gui != null) gui.updateScreen(); } catch (Exception _) {}
        };
        if (gui != null) gui.getGUIThread().invokeLater(repaint);
        else repaint.run();
    }

    /** Renders the current Pokémon card without replacing welcome state. */
    @Override
    public void showWelcomePokemon(PokemonProfile pokemon) {
        Runnable show = () -> {
            if (messagePanel == null || pokemon == null) return;
            int terminalWidth = terminalWidth();
            cardRenderer.show(messagePanel, terminalWidth, pokemon);
            try { if (gui != null) gui.updateScreen(); } catch (Exception _) {}
        };
        if (gui != null) gui.getGUIThread().invokeLater(show);
        else show.run();
    }

    public void addExperience(long tokens) {
        if (tokens <= 0) return;
        ProgressUpdate update;
        synchronized (experienceLock) {
            PokemonProfile current = experienceState;
            PokemonProfile progressed = PokemonEvolution.addExperience(current, tokens);
            if (progressed == null || progressed.equals(current)) return;
            experienceState = progressed;
            update = new ProgressUpdate(current, progressed);
        }
        UiSettings.writeGlobalAsync("welcomePokemon", update.after().toJson())
            .whenComplete((_, failure) -> {
                if (failure != null) {
                    log.warn("Failed to persist welcomePokemon: {}", rootMessage(failure));
                }
            });
        Runnable applyExperience = () -> {
            if (messagePanel == null) return;
            LogoPanel.WelcomeBlock block = welcomeBlock.get();
            if (block == null) return;
            int terminalWidth = terminalWidth();
            welcomeBlock.set(welcomePanel.replacePokemon(
                messagePanel, block, terminalWidth, model.get(), update.after()));
            if (!update.before().name().equals(update.after().name()) && gui != null) {
                PokemonEvolutionOverlay.play(
                    gui, update.before(), update.after(),
                    () -> { if (inputPanel != null) inputPanel.takeFocus(); });
            }
            try { if (gui != null) gui.updateScreen(); } catch (Exception _) {}
        };
        if (gui != null) gui.getGUIThread().invokeLater(applyExperience);
        else applyExperience.run();
    }

    private int terminalWidth() {
        return screen != null ? screen.getTerminalSize().getColumns() : 100;
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
