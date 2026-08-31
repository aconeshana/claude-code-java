package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.Strings;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.ui.lanterna.components.StyledText;


class PluginValidateViewTest {

    @TempDir
    Path tmp;

    private final List<String> recorded = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private PluginSettingsPanel panel;

    private PluginValidateView opened(String args) {
        panel = new PluginSettingsPanel(PluginPanelTestHarness.services(tmp));
        panel.show(PluginRoute.parse(args), (line, _) -> recorded.add(line),
            () -> closed.set(true));
        return panel.validateView();
    }

    private void send(KeyStroke key) {
        panel.handleKey(key, new AtomicBoolean(true));
    }

    @Test
    void validPluginDirectory_passesValidation() throws Exception {
        Path pluginDir = tmp.resolve("good-plugin");
        Files.createDirectories(pluginDir.resolve(".claude-plugin"));
        Files.writeString(pluginDir.resolve(".claude-plugin").resolve("plugin.json"), """
            {"name": "good-plugin", "version": "1.0.0", "description": "d",
             "author": {"name": "a"}}""");
        PluginValidateView view = opened("validate " + pluginDir);
        assertEquals(PluginValidateView.Mode.RESULT, view.mode());
        assertTrue(view.resultSuccess());
        List<String> lines = StyledText.plain(view.buildLines());
        assertTrue(Strings.CS.startsWith(lines.getFirst(), "Validating plugin manifest: "));
        assertTrue(lines.contains("✔ Validation passed"));
        assertTrue(recorded.contains("✔ Validation passed"), "report also goes to the transcript");
    }

    @Test
    void invalidManifest_listsErrorsAndFails() throws Exception {
        Path manifest = tmp.resolve("plugin.json");
        Files.writeString(manifest, """
            {"name": "has spaces", "version": "1.0.0"}""");
        PluginValidateView view = opened("validate " + manifest);
        assertEquals(PluginValidateView.Mode.RESULT, view.mode());
        assertFalse(view.resultSuccess());
        List<String> lines = StyledText.plain(view.buildLines());
        assertTrue(lines.contains("✖ Found 1 error:"));
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, 
            "❯ name: Plugin name cannot contain spaces. Use kebab-case (e.g., \"my-plugin\")")));
        assertTrue(lines.contains("✖ Validation failed"));
    }

    @Test
    void warningsOnly_passWithWarnings() throws Exception {
        Path manifest = tmp.resolve("plugin.json");
        Files.writeString(manifest, """
            {"name": "bare-plugin"}""");
        PluginValidateView view = opened("validate " + manifest);
        assertTrue(view.resultSuccess());
        List<String> lines = StyledText.plain(view.buildLines());
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.startsWith(l, "⚠ Found ")));
        assertTrue(lines.contains("✔ Validation passed with warnings"));
    }

    @Test
    void noPath_offersInputThenRunsOnEnter() throws Exception {
        Path manifest = tmp.resolve("plugin.json");
        Files.writeString(manifest, """
            {"name": "ok-plugin", "version": "1.0.0", "description": "d",
             "author": {"name": "a"}}""");
        PluginValidateView view = opened("validate");
        assertEquals(PluginValidateView.Mode.INPUT, view.mode());
        panel.handleKey(new PasteKeyStroke(manifest.toString()), new AtomicBoolean(true));
        assertEquals(manifest.toString(), view.pathInput(), "PASTE lands in the path input");
        send(new KeyStroke(KeyType.ENTER));
        assertEquals(PluginValidateView.Mode.RESULT, view.mode());
        assertTrue(view.resultSuccess());
    }

    @Test
    void escFromResult_closesPanel() throws Exception {
        Path manifest = tmp.resolve("plugin.json");
        Files.writeString(manifest, "{\"name\": \"x\"}");
        opened("validate " + manifest);
        send(new KeyStroke(KeyType.ESCAPE));
        assertTrue(closed.get());
    }
}
