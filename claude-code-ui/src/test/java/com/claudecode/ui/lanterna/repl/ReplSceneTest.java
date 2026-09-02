package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.ui.lanterna.components.SpinnerComponent;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ReplSceneTest {

    @Test
    void sealingTheSceneRejectsLateComponentsAndOverlays() {
        ReplScene scene = new ReplScene();
        CountingComponent component = new CountingComponent(10, 1);
        CountingOverlay overlay = new CountingOverlay(10, 1);
        scene.mount(component);
        scene.register(overlay);

        scene.seal();

        assertTrue(scene.isSealed());
        assertThrows(IllegalStateException.class,
            () -> scene.mount(new CountingComponent(10, 1)));
        assertThrows(IllegalStateException.class,
            () -> scene.register(new CountingOverlay(10, 1)));
        assertThrows(IllegalStateException.class,
            () -> scene.registerAll(List.of(new CountingOverlay(10, 1))));
    }

    @Test
    void rootRendererDoesNotClearTheFullScreenBeforeChildrenPaintIt() {
        Panel root = ReplScene.createRoot();
        AtomicInteger fills = new AtomicInteger();
        TextGUIGraphics graphics = (TextGUIGraphics) Proxy.newProxyInstance(
            TextGUIGraphics.class.getClassLoader(),
            new Class<?>[] { TextGUIGraphics.class },
            (proxy, method, _) -> switch (method.getName()) {
                case "getSize" -> new TerminalSize(120, 40);
                case "fill" -> {
                    fills.incrementAndGet();
                    yield proxy;
                }
                default -> method.getReturnType().isInstance(proxy) ? proxy : null;
            });

        root.getRenderer().drawComponent(graphics, root);

        assertEquals(0, fills.get(),
            "the fullscreen children cover the scene; an extra 4,800-cell root fill is redundant");
    }

    @Test
    void stableOverlayInputRedrawsOnlyTheInvalidOverlay() {
        Panel root = ReplScene.createRoot();
        CountingComponent transcript = new CountingComponent(80, 20);
        CountingOverlay overlay = new CountingOverlay(80, 8);
        CountingComponent input = new CountingComponent(80, 2);
        overlay.active = true;
        root.addComponent(transcript);
        root.addComponent(overlay);
        root.addComponent(input);
        TextGUIGraphics graphics = graphics(new TerminalSize(80, 24), new AtomicInteger());

        root.draw(graphics);
        transcript.draws.set(0);
        overlay.draws.set(0);
        input.draws.set(0);
        overlay.invalidate();
        root.draw(graphics);

        assertEquals(0, transcript.draws.get());
        assertEquals(1, overlay.draws.get());
        assertEquals(0, input.draws.get());
    }

    @Test
    void activeOverlayPreferredHeightChangeRelayoutsItsGeometry() {
        Panel root = ReplScene.createRoot();
        CountingComponent transcript = new CountingComponent(80, 20);
        CountingOverlay overlay = new CountingOverlay(80, 8);
        CountingComponent input = new CountingComponent(80, 2);
        overlay.active = true;
        root.addComponent(transcript);
        root.addComponent(overlay);
        root.addComponent(input);
        TextGUIGraphics graphics = graphics(new TerminalSize(80, 24), new AtomicInteger());

        root.draw(graphics);
        assertEquals(new TerminalSize(80, 8), overlay.getSize());
        assertEquals(new TerminalPosition(0, 14), overlay.getPosition());

        overlay.resize(80, 20);
        overlay.invalidate();
        root.draw(graphics);

        assertEquals(new TerminalSize(80, 20), overlay.getSize());
        assertEquals(new TerminalPosition(0, 2), overlay.getPosition());
    }

    @Test
    void hidingPinnedSpinnerRelayoutsAndRepaintsTheRowsItPreviouslyCovered() {
        Panel root = ReplScene.createRoot();
        CountingComponent transcript = new CountingComponent(80, 20);
        SpinnerComponent spinner = new SpinnerComponent();
        CountingComponent input = new CountingComponent(80, 2);
        root.addComponent(transcript);
        root.addComponent(spinner);
        root.addComponent(input);
        TextGUIGraphics graphics = graphics(new TerminalSize(80, 24), new AtomicInteger());

        spinner.start("Running");
        root.draw(graphics);
        transcript.draws.set(0);
        spinner.stop();
        input.draws.set(0);

        root.draw(graphics);

        assertEquals(new TerminalPosition(0, 22), input.getPosition(),
            "hiding the spinner must release its pinned row");
        assertTrue(transcript.draws.get() > 0,
            "the transcript must repaint the row exposed by the hidden spinner");
        assertEquals(1, input.draws.get(),
            "the input must repaint at its new position");
        spinner.stop();
    }

    @Test
    void columnOnlyPreferredSizeChange_doesNotRelayoutPinnedFooter() {
        // The spinner animates while a tool runs: every 120ms frame it invalidates and its
        // metric text (timer / token-count string) churns in WIDTH. SmartLayout derives every
        // piece of child geometry from the ROW dimension alone (pinnedH → msgH, and each pinned
        // child is assigned the full terminal width). A column-only preferred-size change must
        // therefore not be classified as a layout change — otherwise doLayout re-runs on every
        // animation frame and re-reads as a brief flicker on the pinned footer while streamed
        // tool output scrolls beneath it.
        Panel root = ReplScene.createRoot();
        CountingComponent transcript = new CountingComponent(80, 20);
        CountingComponent pinned = new CountingComponent(80, 2);  // spinner-like footer
        CountingComponent input = new CountingComponent(80, 2);
        root.addComponent(transcript);
        root.addComponent(pinned);
        root.addComponent(input);
        TextGUIGraphics graphics = graphics(new TerminalSize(80, 24), new AtomicInteger());

        root.draw(graphics);
        assertEquals(new TerminalPosition(0, 20), pinned.getPosition());
        assertEquals(new TerminalPosition(0, 22), input.getPosition());
        transcript.draws.set(0);
        pinned.draws.set(0);
        input.draws.set(0);

        // Column-only churn (identical rows) while the child stays visible and invalid.
        pinned.resize(120, 2);
        pinned.invalidate();
        root.draw(graphics);

        assertEquals(new TerminalPosition(0, 20), pinned.getPosition(),
            "column width must not move the pinned footer geometry");
        assertEquals(1, pinned.draws.get(), "the invalid child itself repaints");
        assertEquals(0, input.draws.get(),
            "a valid pinned sibling must not be repainted by a column-only change");
        assertEquals(0, transcript.draws.get(),
            "the transcript must not be repainted by a column-only pinned-footer change");
    }

    @Test
    void rowChangeStillRelayoutsPinnedFooter() {
        // The row dimension remains the relayout trigger: a spinner growing a foot of extra
        // rows (e.g. a tip line) must relocate the pinned footer and repaint released rows.
        Panel root = ReplScene.createRoot();
        CountingComponent transcript = new CountingComponent(80, 20);
        CountingComponent pinned = new CountingComponent(80, 2);
        CountingComponent input = new CountingComponent(80, 2);
        root.addComponent(transcript);
        root.addComponent(pinned);
        root.addComponent(input);
        TextGUIGraphics graphics = graphics(new TerminalSize(80, 24), new AtomicInteger());

        root.draw(graphics);
        assertEquals(new TerminalPosition(0, 20), pinned.getPosition());
        assertEquals(new TerminalPosition(0, 22), input.getPosition());

        pinned.resize(80, 3);  // rows 2 → 3
        pinned.invalidate();
        root.draw(graphics);

        assertEquals(new TerminalSize(80, 3), pinned.getSize());
        assertEquals(new TerminalPosition(0, 19), pinned.getPosition(),
            "the extra pinned row must push the footer up");
        assertEquals(new TerminalPosition(0, 22), input.getPosition(),
            "the input stays anchored below the taller footer");
    }

    private static TextGUIGraphics graphics(TerminalSize size, AtomicInteger fills) {
        return (TextGUIGraphics) Proxy.newProxyInstance(
            TextGUIGraphics.class.getClassLoader(), new Class<?>[] { TextGUIGraphics.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getSize" -> size;
                case "fill" -> { fills.incrementAndGet(); yield proxy; }
                case "newTextGraphics" -> graphics((TerminalSize) args[1], fills);
                default -> method.getReturnType().isInstance(proxy) ? proxy : null;
            });
    }

    private static class CountingComponent extends AbstractComponent<CountingComponent> {
        private TerminalSize preferred;
        final AtomicInteger draws = new AtomicInteger();

        CountingComponent(int columns, int rows) {
            preferred = new TerminalSize(columns, rows);
        }

        void resize(int columns, int rows) {
            preferred = new TerminalSize(columns, rows);
        }

        @Override protected ComponentRenderer<CountingComponent> createDefaultRenderer() {
            return new ComponentRenderer<>() {
                @Override public TerminalSize getPreferredSize(CountingComponent component) {
                    return preferred;
                }
                @Override public void drawComponent(TextGUIGraphics graphics,
                                                    CountingComponent component) {
                    draws.incrementAndGet();
                }
            };
        }
    }

    private static final class CountingOverlay extends CountingComponent
            implements InlineOverlay {
        boolean active;

        CountingOverlay(int columns, int rows) { super(columns, rows); }
        @Override public boolean isActive() { return active; }
        @Override public void handleKey(KeyStroke key, AtomicBoolean deliver) {}
    }

}
