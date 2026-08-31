package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

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
        enter(dialog);

        assertFalse(dialog.isActive());
        assertEquals("gpt-custom", result.get().modelName());
        assertEquals(ModelApiProtocol.OPENAI_RESPONSES, result.get().protocol());
        assertEquals("secret-key", result.get().apiKey());
        assertEquals(400_000L, result.get().effectiveContextWindow());
        assertEquals("demo", result.get().headers().get("X-Tenant"));
        assertEquals("on", result.get().headers().get("X-Feature"));
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

        assertTrue(dialog.isActive());
        assertNotNull(dialog.errorMessage());
        assertFalse(Strings.CS.contains(dialog.errorMessage(), "secret"));
    }

    @Test
    void arrowKeysNavigateFieldsAndWrap() {
        CustomModelDialog dialog = new CustomModelDialog();
        AtomicReference<CustomModelConfig> result = new AtomicReference<>();
        dialog.show(result::set);

        arrow(dialog, KeyType.ARROW_UP); // model name -> headers
        type(dialog, "X-Tenant: demo");
        arrow(dialog, KeyType.ARROW_DOWN); // headers -> model name
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
        enter(dialog);

        assertFalse(dialog.isActive());
        assertEquals("gpt-arrow", result.get().modelName());
        assertEquals(ModelApiProtocol.OPENAI_CHAT, result.get().protocol());
        assertEquals("https://example.test/v1", result.get().baseUrl());
        assertEquals("secret-key", result.get().apiKey());
        assertEquals(300_000L, result.get().effectiveContextWindow());
        assertEquals("demo", result.get().headers().get("X-Tenant"));
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
