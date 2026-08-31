package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.Strings;
import com.claudecode.runtime.plugins.PluginMarketplacePort.ConfigOption;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.ui.lanterna.components.StyledText;


class PluginOptionsFlowViewTest {

    private static ConfigOption option(String type, String title, Boolean required,
                                            Boolean sensitive) {
        return new ConfigOption(type, title, null, required, null, null, sensitive, null, null);
    }

    private static void type(PluginOptionsFlowView view, String text) {
        for (char c : text.toCharArray()) {
            view.handleKey(new KeyStroke(c, false, false));
        }
    }

    @Test
    void tabAdvances_enterOnLastFieldSavesTypedValues() {
        LinkedHashMap<String, ConfigOption> schema = new LinkedHashMap<>();
        schema.put("host", option("string", "Host", null, null));
        schema.put("port", option("number", "Port", null, null));
        schema.put("secure", option("boolean", "Secure", null, null));
        AtomicReference<Map<String, Object>> saved = new AtomicReference<>();
        PluginOptionsFlowView view = new PluginOptionsFlowView("Configure x", "Plugin options",
            schema, null, new PluginOptionsFlowView.Listener() {
                @Override
                public void onSave(Map<String, Object> values) {
                    saved.set(values);
                }

                @Override
                public void onCancel() {
                }
            });
        type(view, "example.com");
        view.handleKey(new KeyStroke(KeyType.TAB));
        assertEquals(1, view.currentFieldIndex());
        type(view, "8080");
        view.handleKey(new KeyStroke(KeyType.ENTER));
        assertEquals(2, view.currentFieldIndex());
        type(view, "true");
        view.handleKey(new KeyStroke(KeyType.ENTER));
        assertEquals("example.com", saved.get().get("host"));
        assertEquals(8080.0, saved.get().get("port"));
        assertEquals(Boolean.TRUE, saved.get().get("secure"));
    }

    @Test
    void blankNumber_isOmitted_blankBooleanIsFalse() {
        LinkedHashMap<String, ConfigOption> schema = new LinkedHashMap<>();
        schema.put("port", option("number", "Port", null, null));
        schema.put("secure", option("boolean", "Secure", null, null));
        AtomicReference<Map<String, Object>> saved = new AtomicReference<>();
        PluginOptionsFlowView view = new PluginOptionsFlowView("t", "s", schema, null,
            new PluginOptionsFlowView.Listener() {
                @Override
                public void onSave(Map<String, Object> values) {
                    saved.set(values);
                }

                @Override
                public void onCancel() {
                }
            });
        view.handleKey(new KeyStroke(KeyType.ENTER));
        view.handleKey(new KeyStroke(KeyType.ENTER));
        assertFalse(saved.get().containsKey("port"), "blank number omitted (TS Number('')→0 guard)");
        assertEquals(Boolean.FALSE, saved.get().get("secure"));
    }

    @Test
    void sensitiveField_masksInputAndKeepsExistingSecretWhenBlank() {
        LinkedHashMap<String, ConfigOption> schema = new LinkedHashMap<>();
        schema.put("token", option("string", "Token", null, true));
        AtomicReference<Map<String, Object>> saved = new AtomicReference<>();
        PluginOptionsFlowView view = new PluginOptionsFlowView("t", "s", schema,
            Map.of("token", "old-secret"), new PluginOptionsFlowView.Listener() {
                @Override
                public void onSave(Map<String, Object> values) {
                    saved.set(values);
                }

                @Override
                public void onCancel() {
                }
            });
        type(view, "ab");
        List<String> lines = StyledText.plain(view.buildLines());
        assertTrue(lines.contains("› **█"), "sensitive input masked");
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, 
            "Sensitive value — stored in secure credentials storage")));
        // Clear the buffer → blank sensitive with an existing value is omitted.
        view.handleKey(new KeyStroke(KeyType.BACKSPACE));
        view.handleKey(new KeyStroke(KeyType.BACKSPACE));
        view.handleKey(new KeyStroke(KeyType.ENTER));
        assertFalse(saved.get().containsKey("token"), "omitting keeps the stored secret");
    }

    @Test
    void requiredValidation_jumpsBackToMissingField() {
        LinkedHashMap<String, ConfigOption> schema = new LinkedHashMap<>();
        schema.put("key", option("string", "API Key", true, null));
        schema.put("note", option("string", "Note", null, null));
        AtomicBoolean savedFlag = new AtomicBoolean(false);
        PluginOptionsFlowView view = new PluginOptionsFlowView("t", "s", schema, null,
            new PluginOptionsFlowView.Listener() {
                @Override
                public void onSave(Map<String, Object> values) {
                    savedFlag.set(true);
                }

                @Override
                public void onCancel() {
                }
            });
        view.handleKey(new KeyStroke(KeyType.ENTER)); // skip required
        type(view, "hello");
        view.handleKey(new KeyStroke(KeyType.ENTER)); // save attempt
        assertFalse(savedFlag.get());
        assertEquals("API Key is required", view.error());
        assertEquals(0, view.currentFieldIndex(), "jumps back to the missing field");
    }

    @Test
    void pasteAndEscape_workInsideFields() {
        LinkedHashMap<String, ConfigOption> schema = new LinkedHashMap<>();
        schema.put("dir", option("directory", "Directory", null, null));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<Map<String, Object>> saved = new AtomicReference<>();
        PluginOptionsFlowView view = new PluginOptionsFlowView("t", "s", schema, null,
            new PluginOptionsFlowView.Listener() {
                @Override
                public void onSave(Map<String, Object> values) {
                    saved.set(values);
                }

                @Override
                public void onCancel() {
                    cancelled.set(true);
                }
            });
        view.handleKey(new PasteKeyStroke("/tmp/some\ndir"));
        view.handleKey(new KeyStroke(KeyType.ENTER));
        assertEquals("/tmp/some dir", saved.get().get("dir"), "paste flattened into the field");
        view.handleKey(new KeyStroke(KeyType.ESCAPE));
        assertTrue(cancelled.get());
    }

    @Test
    void footerShowsFieldProgressAndKeyHints() {
        LinkedHashMap<String, ConfigOption> schema = new LinkedHashMap<>();
        schema.put("a", option("string", "A", null, null));
        schema.put("b", option("string", "B", null, null));
        PluginOptionsFlowView view = new PluginOptionsFlowView("Configure x", "Plugin options",
            schema, null, new PluginOptionsFlowView.Listener() {
                @Override
                public void onSave(Map<String, Object> values) {
                }

                @Override
                public void onCancel() {
                }
            });
        List<String> lines = StyledText.plain(view.buildLines());
        assertEquals("Configure x", lines.getFirst());
        assertEquals("Plugin options", lines.get(1));
        assertTrue(lines.contains("Field 1 of 2"));
        assertTrue(lines.contains("Tab: Next field · Enter: Save and continue"));
        view.handleKey(new KeyStroke(KeyType.TAB));
        lines = StyledText.plain(view.buildLines());
        assertTrue(lines.contains("Field 2 of 2"));
        assertTrue(lines.contains("Enter: Save configuration"));
        assertNull(view.error());
    }
}
