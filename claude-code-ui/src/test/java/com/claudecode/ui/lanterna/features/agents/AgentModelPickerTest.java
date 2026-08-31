package com.claudecode.ui.lanterna.features.agents;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentModelPicker}.
 */
class AgentModelPickerTest {

    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);

    private static void send(AgentModelPicker p, KeyStroke k) {
        p.handleKey(k, new AtomicBoolean(true));
    }

    @Test
    void activate_seedsSelectionFromCurrentModel() {
        AgentModelPicker p = new AgentModelPicker();
        p.activate("opus", _ -> {}, () -> {});
        assertEquals("opus", p.selectedModel());
    }

    @Test
    void activate_nullModel_seedsSonnet() {
        AgentModelPicker p = new AgentModelPicker();
        p.activate(null, _ -> {}, () -> {});
        assertEquals("sonnet", p.selectedModel());
    }

    @Test
    void activate_customModelPrependsAndSelectsCurrentCustomId() {
        AgentModelPicker p = new AgentModelPicker();
        p.activate("vendor/custom-model", _ -> {}, () -> {});

        assertEquals("vendor/custom-model", p.selectedModel());
        assertTrue(render(p).contains("Current model (custom ID)"));
    }

    @Test
    void navigation_cyclesThroughFiveOptions() {
        AgentModelPicker p = new AgentModelPicker();
        p.activate("sonnet", _ -> {}, () -> {});
        send(p, DOWN); // sonnet -> opus
        assertEquals("opus", p.selectedModel());
        send(p, DOWN); // opus -> haiku
        assertEquals("haiku", p.selectedModel());
        send(p, DOWN); // haiku -> inherit
        assertEquals("inherit", p.selectedModel());
        send(p, DOWN); // inherit -> fable (wrap)
        assertEquals("fable", p.selectedModel());
        send(p, DOWN); // fable -> sonnet (full cycle)
        assertEquals("sonnet", p.selectedModel());
    }

    @Test
    void enter_confirmsSelectedModel() {
        AgentModelPicker p = new AgentModelPicker();
        String[] result = {"unset"};
        p.activate("sonnet", m -> result[0] = m, () -> {});
        send(p, DOWN);
        send(p, ENTER);
        assertEquals("opus", result[0]);
        assertFalse(p.isPickerVisible());
    }

    @Test
    void esc_cancelsWithoutConfirming() {
        AgentModelPicker p = new AgentModelPicker();
        boolean[] confirmed = {false};
        boolean[] cancelled = {false};
        p.activate("sonnet", _ -> confirmed[0] = true, () -> cancelled[0] = true);
        send(p, ESC);
        assertFalse(confirmed[0]);
        assertTrue(cancelled[0]);
    }

    @Test
    void upFromSonnet_wrapsToFable() {
        AgentModelPicker p = new AgentModelPicker();
        p.activate("sonnet", _ -> {}, () -> {});
        send(p, UP);
        assertEquals("fable", p.selectedModel());
    }

    @Test
    void numericIndexImmediatelySelectsTheMatchingOption() {
        AgentModelPicker p = new AgentModelPicker();
        String[] result = {null};
        p.activate("sonnet", model -> result[0] = model, () -> {});

        send(p, new KeyStroke('3', false, false));

        assertEquals("opus", result[0]);
        assertFalse(p.isPickerVisible());
    }

    @Test
    void fullWidthNumericIndexSelectsButOtherUnicodeDigitsDoNot() {
        AgentModelPicker p = new AgentModelPicker();
        String[] result = {null};
        p.activate("sonnet", model -> result[0] = model, () -> {});

        send(p, new KeyStroke('\u0662', false, false));

        assertNull(result[0]);
        assertTrue(p.isPickerVisible());

        send(p, new KeyStroke('\uFF12', false, false));

        assertEquals("sonnet", result[0]);
        assertFalse(p.isPickerVisible());
    }

    @Test
    void pageKeysMoveByTheReleasedFiveOptionPage() {
        AgentModelPicker p = new AgentModelPicker();
        p.activate("fable", _ -> {}, () -> {});

        send(p, new KeyStroke(KeyType.PAGE_DOWN));
        assertEquals("inherit", p.selectedModel());
        send(p, new KeyStroke(KeyType.PAGE_UP));
        assertEquals("fable", p.selectedModel());
    }

    @Test
    void renderMatchesReleasedIndexedSelectCopy() {
        AgentModelPicker p = new AgentModelPicker();
        p.activate(null, _ -> {}, () -> {});

        String rendered = render(p);
        assertTrue(rendered.contains("Create new agent"), rendered);
        assertTrue(rendered.contains("Select model"), rendered);
        assertTrue(rendered.contains(
            "Model determines the agent's reasoning capabilities and speed."), rendered);
        assertTrue(rendered.contains("1. Fable"), rendered);
        assertTrue(rendered.contains("2. Sonnet"), rendered);
        assertTrue(rendered.contains("Esc go back"), rendered);
    }

    @Test
    void editPresentationUsesOuterDialogTitleWithoutWizardFooter() {
        AgentModelPicker p = new AgentModelPicker();
        p.activate("opus", _ -> {}, () -> {}, "Edit agent: reviewer", null,
            false, LanternaTheme.permission());

        String rendered = render(p);
        assertTrue(rendered.contains("Edit agent: reviewer"), rendered);
        assertFalse(rendered.contains("Create new agent"), rendered);
        assertFalse(rendered.contains("Select model"), rendered);
        assertFalse(rendered.contains("Esc go back"), rendered);
    }

    @Test
    void selectBindingsCanBeRebound(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("keybindings.json");
        Files.writeString(file, """
            [{"context":"Select","bindings":{
              "x":"select:next","z":"select:accept","q":"select:cancel",
              "down":null,"enter":null,"escape":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            AgentModelPicker p = new AgentModelPicker();
            p.setKeybindingsStore(store);
            boolean[] cancelled = {false};
            p.activate("sonnet", _ -> {}, () -> cancelled[0] = true);

            send(p, DOWN);
            assertEquals("sonnet", p.selectedModel());
            send(p, new KeyStroke('x', false, false));
            assertEquals("opus", p.selectedModel());
            send(p, ESC);
            assertTrue(p.isPickerVisible());
            send(p, new KeyStroke('q', false, false));
            assertTrue(cancelled[0]);
        } finally {
            store.dispose();
        }
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    private static String render(AgentModelPicker picker) {
        TerminalSize size = new TerminalSize(90, 16);
        picker.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        picker.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        return imageText(image, size);
    }

    private static String imageText(BasicTextImage image, TerminalSize size) {
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < size.getRows(); row++) {
            for (int column = 0; column < size.getColumns(); column++) {
                text.append(image.getCharacterAt(column, row).getCharacterString());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
