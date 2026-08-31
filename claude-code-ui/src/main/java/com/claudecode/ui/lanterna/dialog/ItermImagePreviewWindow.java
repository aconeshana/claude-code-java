package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.ui.TerminalDetector;
import com.claudecode.ui.lanterna.repl.TuiOutputGuard;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.SelectionAwareTextGUI;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.Strings;

/** Full-screen iTerm2 inline-image preview hosted inside Lanterna. */
@Explanation("Displays explicit imgcat previews inside the Java TUI using iTerm2's image protocol")
public final class ItermImagePreviewWindow extends BasicWindow {

    private static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;
    private static final TextColor BACKGROUND = new TextColor.RGB(12, 12, 14);

    private final SelectionAwareTextGUI gui;
    private final Window previousWindow;
    private final Path image;
    private final String encodedImage;
    private final long imageSize;
    private final Runnable onClose;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ItermImagePreviewWindow(SelectionAwareTextGUI gui, Path image,
                                    String encodedImage, long imageSize, Runnable onClose) {
        this.gui = gui;
        this.previousWindow = gui.getActiveWindow();
        this.image = image;
        this.encodedImage = encodedImage;
        this.imageSize = imageSize;
        this.onClose = onClose != null ? onClose : () -> {};
        setHints(Set.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS,
            Window.Hint.NO_POST_RENDERING, Window.Hint.FIT_TERMINAL_WINDOW,
            Window.Hint.MODAL));
        setComponent(new PreviewCanvas(image));
    }

    public static void show(SelectionAwareTextGUI gui, Path image,
                            Runnable onClose) throws IOException {
        if (gui == null || image == null) throw new IOException("Image preview is unavailable");
        if (!Strings.CS.equalsAny(TerminalDetector.getTerminal(), "iTerm.app", "iTerm2")) {
            throw new IOException("imgcat TUI preview currently requires iTerm2");
        }
        long size = Files.size(image);
        if (size > MAX_IMAGE_BYTES) {
            throw new IOException("Image is too large to preview (maximum 32 MB)");
        }
        String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(image));
        ItermImagePreviewWindow preview = new ItermImagePreviewWindow(
            gui, image, encoded, size, onClose);
        gui.getGUIThread().invokeLater(preview::open);
    }

    private void open() {
        gui.addWindow(this);
        gui.setActiveWindow(this);
        try {
            gui.updateScreen();
            gui.getScreen().refresh(Screen.RefreshType.COMPLETE);
            emitImage();
        } catch (IOException _) {
            close();
        }
    }

    private void emitImage() {
        TerminalSize size = gui.getScreen().getTerminalSize();
        int columns = Math.max(1, size.getColumns() - 4);
        int rows = Math.max(1, size.getRows() - 5);
        String name = Base64.getEncoder().encodeToString(
            image.getFileName().toString().getBytes(StandardCharsets.UTF_8));
        TuiOutputGuard.writeToTerminal(
            imageSequence(encodedImage, name, imageSize, columns, rows));
    }

    static String imageSequence(String encodedImage, String encodedName,
                                long imageSize, int columns, int rows) {
        return "\0337\033[3;3H\033]1337;File=name=" + encodedName
            + ";size=" + imageSize + ";inline=1;width=" + columns + ";height=" + rows
            + ";preserveAspectRatio=1:" + encodedImage
            + "\007\0338";
    }

    @Override
    public boolean handleInput(KeyStroke key) {
        switch (key.getKeyType()) {
            case ENTER, ESCAPE -> close();
            case CHARACTER -> {
                if (Character.valueOf('q').equals(key.getCharacter())) close();
            }
            default -> { }
        }
        return true;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        boolean wasActive = gui.getActiveWindow() == this;
        super.close();
        if (wasActive && previousWindow != null) gui.setActiveWindow(previousWindow);
        gui.getGUIThread().invokeLater(() -> {
            try {
                gui.updateScreen();
                gui.getScreen().refresh(
                    Screen.RefreshType.COMPLETE);
            } catch (IOException _) { }
            onClose.run();
        });
    }

    private static final class PreviewCanvas extends AbstractComponent<PreviewCanvas> {
        private final Path image;

        private PreviewCanvas(Path image) {
            this.image = image;
        }

        @Override
        protected ComponentRenderer<PreviewCanvas> createDefaultRenderer() {
            return new Renderer();
        }

        private final class Renderer implements ComponentRenderer<PreviewCanvas> {
            @Override
            public TerminalSize getPreferredSize(PreviewCanvas component) {
                return new TerminalSize(80, 24);
            }

            @Override
            public void drawComponent(TextGUIGraphics graphics, PreviewCanvas component) {
                TerminalSize size = graphics.getSize();
                graphics.setBackgroundColor(BACKGROUND);
                graphics.fill(' ');
                graphics.setForegroundColor(LanternaTheme.claude());
                graphics.putString(2, 0, "Image preview · " + image.getFileName());
                graphics.setForegroundColor(LanternaTheme.welcomeDim());
                graphics.putString(2, Math.max(0, size.getRows() - 1),
                    "Enter/Esc/q close");
            }
        }
    }
}
