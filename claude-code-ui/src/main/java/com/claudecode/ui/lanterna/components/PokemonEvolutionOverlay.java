package com.claudecode.ui.lanterna.components;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.pokemon.PokemonProfile;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.input.KeyStroke;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Short-lived fullscreen Pokémon evolution cut-in.
 */
@Explanation("Non-blocking fullscreen evolution cut-in for the UI-only welcome Pokémon.")
public final class PokemonEvolutionOverlay extends BasicWindow {

    private static final TextColor BACKGROUND = new TextColor.RGB(10, 10, 14);
    private static final TextColor FLASH = new TextColor.RGB(255, 248, 205);
    private static final TextColor GOLD = new TextColor.RGB(255, 211, 92);

    enum Stage { OLD, FLASH_OLD, GLOW, FLASH_NEW, NEW }
    record Frame(Stage stage, long durationMs) {}

    private final MultiWindowTextGUI gui;
    private final Window previousWindow;
    private final Runnable restoreFocus;
    private final EvolutionCanvas canvas;
    private final List<Frame> frames;
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual()
            .name("pokemon-evolution-overlay", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private int frameIndex;
    private ScheduledFuture<?> nextFrame;

    private PokemonEvolutionOverlay(MultiWindowTextGUI gui, Window previousWindow,
                                    PokemonProfile before, PokemonProfile after,
                                    Runnable restoreFocus) {
        this.gui = gui;
        this.previousWindow = previousWindow;
        this.restoreFocus = restoreFocus == null ? () -> { } : restoreFocus;
        this.frames = animationFrames();
        this.canvas = new EvolutionCanvas(before, after, () -> frames.get(frameIndex).stage());
        setHints(Set.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS,
            Window.Hint.NO_POST_RENDERING, Window.Hint.FIT_TERMINAL_WINDOW,
            Window.Hint.MODAL));
        setComponent(canvas);
    }

    public static void play(MultiWindowTextGUI gui, PokemonProfile before,
                            PokemonProfile after, Runnable restoreFocus) {
        if (gui == null || before == null || after == null
                || before.name().equals(after.name())) return;
        Window previous = gui.getActiveWindow();
        PokemonEvolutionOverlay overlay = new PokemonEvolutionOverlay(
            gui, previous, before, after, restoreFocus);
        gui.addWindow(overlay);
        gui.setActiveWindow(overlay);
        overlay.scheduleNextFrame();
    }

    static List<Frame> animationFrames() {
        return List.of(
            new Frame(Stage.OLD, 1_000),
            new Frame(Stage.FLASH_OLD, 500),
            new Frame(Stage.GLOW, 2_000),
            new Frame(Stage.FLASH_NEW, 500),
            new Frame(Stage.NEW, 3_000));
    }

    private void scheduleNextFrame() {
        if (closed.get()) return;
        long delay = frames.get(frameIndex).durationMs();
        nextFrame = scheduler.schedule(() -> gui.getGUIThread().invokeLater(() -> {
            if (closed.get()) return;
            frameIndex++;
            if (frameIndex >= frames.size()) {
                finish();
                return;
            }
            canvas.invalidate();
            scheduleNextFrame();
        }), delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean handleInput(KeyStroke key) {
        // The cut-in intentionally owns focus for a moment. Escape/Enter/Space
        // provide an immediate dismissal without leaking input to the prompt.
        switch (key.getKeyType()) {
            case ESCAPE, ENTER -> finish();
            case CHARACTER -> {
                if (Character.valueOf(' ').equals(key.getCharacter())) finish();
            }
            default -> { }
        }
        return true;
    }

    @Override
    public void close() {
        finish();
    }

    private void finish() {
        if (!closed.compareAndSet(false, true)) return;
        if (nextFrame != null) nextFrame.cancel(false);
        scheduler.shutdownNow();
        boolean stillActive = gui.getActiveWindow() == this;
        super.close();
        if (stillActive && previousWindow != null) {
            gui.setActiveWindow(previousWindow);
            restoreFocus.run();
        }
    }

    private static final class EvolutionCanvas extends AbstractComponent<EvolutionCanvas> {
        private final PokemonProfile before;
        private final PokemonProfile after;
        private final Supplier<Stage> stage;
        private final LogoPanel.SpriteArtwork oldArtwork;
        private final LogoPanel.SpriteArtwork newArtwork;

        private EvolutionCanvas(PokemonProfile before, PokemonProfile after,
                                Supplier<Stage> stage) {
            this.before = before;
            this.after = after;
            this.stage = stage;
            oldArtwork = LogoPanel.spriteArtwork(before);
            newArtwork = LogoPanel.spriteArtwork(after);
        }

        @Override
        protected ComponentRenderer<EvolutionCanvas> createDefaultRenderer() {
            return new EvolutionRenderer();
        }

        @Override
        public TerminalSize calculatePreferredSize() {
            return new TerminalSize(80, 24);
        }

        private final class EvolutionRenderer implements ComponentRenderer<EvolutionCanvas> {
            @Override
            public TerminalSize getPreferredSize(EvolutionCanvas component) {
                return component.calculatePreferredSize();
            }

            @Override
            public void drawComponent(TextGUIGraphics graphics, EvolutionCanvas component) {
                TerminalSize size = graphics.getSize();
                fill(graphics, size, BACKGROUND);
                Stage current = stage.get();
                LogoPanel.SpriteArtwork artwork = switch (current) {
                    case FLASH_NEW, NEW -> newArtwork;
                    case OLD, FLASH_OLD, GLOW -> oldArtwork;
                };
                int contentHeight = artwork.height() + 5;
                int top = Math.max(0, (size.getRows() - contentHeight) / 2);
                String title = before.shiny() || after.shiny()
                    ? "✦  SHINY EVOLUTION  ✦" : "✦  EVOLUTION  ✦";
                drawCentered(graphics, top, title, GOLD, Set.of(SGR.BOLD));

                boolean flash = current == Stage.FLASH_OLD || current == Stage.FLASH_NEW;
                boolean glow = current == Stage.GLOW;
                int spriteLeft = Math.max(0, (size.getColumns() - artwork.width()) / 2);
                for (int row = 0; row < artwork.rows().size(); row++) {
                    drawSpriteRow(graphics, spriteLeft, top + 2 + row,
                        artwork.rows().get(row), flash, glow, size.getColumns());
                }

                String message = current == Stage.NEW
                    ? before.displayName() + " evolved into " + after.displayName() + "!"
                    : before.displayName() + " is evolving...";
                TextColor messageColor = current == Stage.NEW
                    ? LanternaTheme.toolSuccess() : GOLD;
                drawCentered(graphics, top + 3 + artwork.height(), message,
                    messageColor, Set.of(SGR.BOLD));
            }
        }
    }

    private static void fill(TextGUIGraphics graphics, TerminalSize size, TextColor background) {
        TextCharacter cell = TextCharacter.fromCharacter(' ', TextColor.ANSI.DEFAULT, background);
        for (int y = 0; y < size.getRows(); y++) {
            for (int x = 0; x < size.getColumns(); x++) graphics.setCharacter(x, y, cell);
        }
    }

    private static void drawCentered(TextGUIGraphics graphics, int y, String text,
                                     TextColor color, Set<SGR> modifiers) {
        int x = Math.max(0, (graphics.getSize().getColumns() - FormatUtils.displayWidth(text)) / 2);
        TextCharacter[] cells = TextCharacter.fromString(text, color, BACKGROUND,
            modifiers.toArray(SGR[]::new));
        for (TextCharacter cell : cells) {
            if (x >= graphics.getSize().getColumns()) break;
            graphics.setCharacter(x, y, cell);
            x += TerminalTextUtils.isCharDoubleWidth(cell.getCharacterString().charAt(0)) ? 2 : 1;
        }
    }

    private static void drawSpriteRow(TextGUIGraphics graphics, int startX, int y,
                                      List<MessagePanel.Segment> segments,
                                      boolean flash, boolean glow, int width) {
        int x = startX;
        for (MessagePanel.Segment segment : segments) {
            TextColor foreground = flash ? FLASH
                : glow ? GOLD : segment.color();
            TextColor background = segment.bgColor();
            if (background != null && (flash || glow)) background = flash ? FLASH : GOLD;
            if (background == null) background = BACKGROUND;
            for (int index = 0; index < segment.text().length() && x < width; index++) {
                char character = segment.text().charAt(index);
                graphics.setCharacter(x, y,
                    TextCharacter.fromCharacter(character, foreground, background));
                x += TerminalTextUtils.isCharDoubleWidth(character) ? 2 : 1;
            }
        }
    }
}
