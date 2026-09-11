package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Add-custom-model form behavior. */
class CustomModelDialogTest {

    @Test
    void capturesProtocolBaseUrlKeyModelAndHeaders() {
        CustomModelDialog dialog = new CustomModelDialog();
        AtomicReference<CustomModelConfig> result = new AtomicReference<>();
        dialog.show(result::set);

        type(dialog, "gpt-custom");
        enter(dialog); // protocol
        enter(dialog); // accept default Responses, move to base URL
        type(dialog, "https://example.test/v1");
        enter(dialog);
        type(dialog, "secret-key");
        enter(dialog);
        type(dialog, "400000");
        enter(dialog);
        type(dialog, "X-Tenant: demo; X-Feature: on");
        enter(dialog); // move to multimodal
        enter(dialog); // keep default (yes), submit

        assertFalse(dialog.isActive());
        assertEquals("gpt-custom", result.get().modelName());
        assertEquals(ModelApiProtocol.OPENAI_RESPONSES, result.get().protocol());
        assertEquals("secret-key", result.get().apiKey());
        assertEquals(400_000L, result.get().effectiveContextWindow());
        assertEquals("demo", result.get().headers().get("X-Tenant"));
        assertEquals("on", result.get().headers().get("X-Feature"));
        assertNull(result.get().multimodal(),
            "an untouched multimodal field stays unconfigured");
    }

    @Test
    void multimodalToggleCyclesYesNoAndUnset() {
        CustomModelDialog dialog = new CustomModelDialog();
        AtomicReference<CustomModelConfig> result = new AtomicReference<>();
        dialog.show(result::set);

        type(dialog, "text-only-model");
        enter(dialog); // protocol
        enter(dialog); // base URL
        type(dialog, "https://example.test/v1");
        enter(dialog); // API key
        enter(dialog); // context window
        enter(dialog); // headers
        enter(dialog); // multimodal
        // multimodal: (unset) --right--> yes --right--> no
        arrow(dialog, KeyType.ARROW_RIGHT);
        arrow(dialog, KeyType.ARROW_RIGHT);
        enter(dialog); // submit with no

        assertFalse(dialog.isActive());
        assertEquals(Boolean.FALSE, result.get().multimodal());
        assertTrue(result.get().isTextOnly());

        // ← reverses: no --left--> yes
        AtomicReference<CustomModelConfig> second = new AtomicReference<>();
        dialog.show(second::set);
        type(dialog, "another");
        enter(dialog); // protocol
        enter(dialog); // base URL
        type(dialog, "https://example.test/v1");
        enter(dialog); // API key
        enter(dialog); // context window
        enter(dialog); // headers
        enter(dialog); // multimodal
        arrow(dialog, KeyType.ARROW_RIGHT);
        arrow(dialog, KeyType.ARROW_RIGHT);
        arrow(dialog, KeyType.ARROW_LEFT); // back to yes
        enter(dialog);
        assertEquals(Boolean.TRUE, second.get().multimodal());
    }

    @Test
    void invalidBaseUrlKeepsFormOpenAndShowsSafeError() {
        CustomModelDialog dialog = new CustomModelDialog();
        dialog.show(_ -> fail("invalid form must not resolve"));
        type(dialog, "model");
        enter(dialog);
        enter(dialog);
        type(dialog, "not-a-url");
        enter(dialog);
        enter(dialog);
        enter(dialog);
        enter(dialog);
        enter(dialog);

        assertTrue(dialog.isActive());
        assertNotNull(dialog.errorMessage());
        assertFalse(Strings.CS.contains(dialog.errorMessage(), "secret"));
    }

    @Test
    void arrowKeysNavigateFieldsAndWrap() {
        CustomModelDialog dialog = new CustomModelDialog();
        AtomicReference<CustomModelConfig> result = new AtomicReference<>();
        dialog.show(result::set);

        arrow(dialog, KeyType.ARROW_UP); // model name -> multimodal
        arrow(dialog, KeyType.ARROW_UP); // multimodal -> headers
        type(dialog, "X-Tenant: demo");
        arrow(dialog, KeyType.ARROW_DOWN); // headers -> model name (wraps past multimodal? no: 5->6)
        arrow(dialog, KeyType.ARROW_UP); // 6 -> 5 (stay deterministic on headers)
        arrow(dialog, KeyType.ARROW_UP); // 5 -> 4
        arrow(dialog, KeyType.ARROW_UP); // 4 -> 3
        arrow(dialog, KeyType.ARROW_UP); // 3 -> 2
        arrow(dialog, KeyType.ARROW_UP); // 2 -> 1 protocol
        arrow(dialog, KeyType.ARROW_UP); // 1 -> 0 model name
        type(dialog, "gpt-arrow");
        arrow(dialog, KeyType.ARROW_DOWN); // protocol
        arrow(dialog, KeyType.ARROW_RIGHT); // Responses -> Chat
        arrow(dialog, KeyType.ARROW_DOWN); // base URL
        type(dialog, "https://example.test/v1");
        arrow(dialog, KeyType.ARROW_DOWN); // API key
        type(dialog, "secret-key");
        arrow(dialog, KeyType.ARROW_DOWN); // context window
        type(dialog, "300000");
        arrow(dialog, KeyType.ARROW_DOWN); // headers
        enter(dialog); // headers -> multimodal
        enter(dialog); // submit

        assertFalse(dialog.isActive());
        assertEquals("gpt-arrow", result.get().modelName());
        assertEquals(ModelApiProtocol.OPENAI_CHAT, result.get().protocol());
        assertEquals("https://example.test/v1", result.get().baseUrl());
        assertEquals("secret-key", result.get().apiKey());
        assertEquals(300_000L, result.get().effectiveContextWindow());
        assertEquals("demo", result.get().headers().get("X-Tenant"));
        assertNull(result.get().multimodal());
    }

    @Test
    void editPathPrefillsAllFieldsIncludingHeadersAndMultimodal() {
        CustomModelDialog dialog = new CustomModelDialog();
        AtomicReference<CustomModelConfig> result = new AtomicReference<>();
        CustomModelConfig existing = new CustomModelConfig("legacy-alias",
            ModelApiProtocol.ANTHROPIC, "https://example.test/v1", "stored-key",
            Map.of("X-Tenant", "demo", "X-Feature", "on"), 250_000L, Boolean.FALSE);
        dialog.show(existing, result::set);

        // Walk down to multimodal (field 6), toggle the prefilled no to yes, submit.
        for (int i = 0; i < 6; i++) arrow(dialog, KeyType.ARROW_DOWN);
        arrow(dialog, KeyType.ARROW_LEFT); // no -> yes
        enter(dialog); // submit

        assertFalse(dialog.isActive());
        assertEquals("legacy-alias", result.get().modelName());
        assertEquals(ModelApiProtocol.ANTHROPIC, result.get().protocol());
        assertEquals("https://example.test/v1", result.get().baseUrl());
        assertEquals("stored-key", result.get().apiKey());
        assertEquals("demo", result.get().headers().get("X-Tenant"),
            "an edit round-trip keeps the stored headers");
        assertEquals("on", result.get().headers().get("X-Feature"));
        assertEquals(250_000L, result.get().effectiveContextWindow());
        assertEquals(Boolean.TRUE, result.get().multimodal());
    }

    private static void type(CustomModelDialog dialog, String value) {
        for (char c : value.toCharArray()) {
            dialog.handleKey(new KeyStroke(c, false, false), new AtomicBoolean(true));
        }
    }

    private static void enter(CustomModelDialog dialog) {
        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
    }

    private static void arrow(CustomModelDialog dialog, KeyType keyType) {
        AtomicBoolean deliver = new AtomicBoolean(true);
        dialog.handleKey(new KeyStroke(keyType), deliver);
        assertFalse(deliver.get());
    }
}
