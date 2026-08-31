package com.claudecode.api;

import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.lang3.Strings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Persistence contract for the custom-model catalogue. */
class CustomModelJsonStoreTest {

    @TempDir Path tempDir;

    @Test
    void savesLoadsAndReplacesModelsWithoutExposingSecretsInToString() throws Exception {
        Path file = tempDir.resolve("model.json");
        CustomModelJsonStore store = new CustomModelJsonStore(file);
        var first = new CustomModelConfig("gpt-test", ModelApiProtocol.OPENAI_RESPONSES,
            "http://127.0.0.1:8317/v1/", "secret-one", Map.of("X-Tenant", "demo"), 400_000L);

        store.save(first);
        assertEquals("http://127.0.0.1:8317/v1", store.find("gpt-test").orElseThrow().baseUrl());
        assertEquals(400_000L, store.find("gpt-test").orElseThrow().effectiveContextWindow());
        assertFalse(Strings.CS.contains(first.toString(), "secret-one"));

        store.save(new CustomModelConfig("gpt-test", ModelApiProtocol.OPENAI_CHAT,
            "https://example.test/v1", "secret-two", Map.of()));

        assertEquals(1, store.list().size());
        assertEquals(ModelApiProtocol.OPENAI_CHAT, store.find("gpt-test").orElseThrow().protocol());
        assertTrue(Strings.CS.contains(Files.readString(file), "secret-two"));
    }

    @Test
    void legacyModelsWithoutContextWindowUseThe200kDefault() throws Exception {
        Path file = tempDir.resolve("model.json");
        Files.writeString(file, """
            {"version":1,"models":[{"modelName":"legacy","protocol":"responses",
            "baseUrl":"https://example.test/v1","apiKey":null,"headers":{}}]}
            """);

        CustomModelConfig model = new CustomModelJsonStore(file).find("legacy").orElseThrow();
        assertNull(model.contextWindow());
        assertEquals(CustomModelConfig.DEFAULT_CONTEXT_WINDOW, model.effectiveContextWindow());
        assertNull(new CustomModelJsonStore(file).contextWindow("legacy"),
            "missing contextWindow must remain unknown to proactive auto-compact");
    }

    @Test
    void legacyGpt56WithoutContextWindowUses372kBuiltInDefault() throws Exception {
        Path file = tempDir.resolve("model.json");
        Files.writeString(file, """
            {"version":1,"models":[{"modelName":"gpt-5.6-sol","protocol":"responses",
            "baseUrl":"https://example.test/v1","apiKey":null,"headers":{}}]}
            """);

        CustomModelJsonStore store = new CustomModelJsonStore(file);
        CustomModelConfig model = store.find("gpt-5.6-sol").orElseThrow();
        assertNull(model.contextWindow());
        assertEquals(372_000L, model.effectiveContextWindow());
        assertNull(store.contextWindow("gpt-5.6-sol"),
            "catalogue exposes only user-entered windows; built-in fallback is resolved later");
    }

    @Test
    void catalogueExposesAnExplicitContextWindowToAutoCompact() {
        CustomModelJsonStore store = new CustomModelJsonStore(tempDir.resolve("model.json"));
        store.save(new CustomModelConfig("explicit", ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", null, Map.of(), 400_000L));

        assertEquals(400_000L, store.contextWindow("explicit"));
    }

    @Test
    void removesOnlyTheExactNamedModelAndItsCredentials() throws Exception {
        Path file = tempDir.resolve("model.json");
        CustomModelJsonStore store = new CustomModelJsonStore(file);
        store.save(new CustomModelConfig("gpt-alpha", ModelApiProtocol.OPENAI_RESPONSES,
            "https://alpha.example/v1", "alpha-secret", Map.of("X-Alpha", "private")));
        store.save(new CustomModelConfig("gpt-beta", ModelApiProtocol.OPENAI_CHAT,
            "https://beta.example/v1", "beta-secret", Map.of()));

        assertFalse(store.remove("GPT-ALPHA"), "model names are exact and case-sensitive");
        assertTrue(store.remove("gpt-alpha"));

        assertTrue(store.find("gpt-alpha").isEmpty());
        assertTrue(store.find("gpt-beta").isPresent());
        String persisted = Files.readString(file);
        assertFalse(Strings.CS.contains(persisted, "alpha-secret"));
        assertFalse(Strings.CS.contains(persisted, "private"));
        assertTrue(Strings.CS.contains(persisted, "beta-secret"));
    }

    @Test
    void removingMissingOrBlankModelDoesNotRewriteTheCatalogue() throws Exception {
        Path file = tempDir.resolve("model.json");
        CustomModelJsonStore store = new CustomModelJsonStore(file);
        store.save(new CustomModelConfig("kept", ModelApiProtocol.ANTHROPIC,
            "https://api.example/v1", "secret", Map.of()));
        String before = Files.readString(file);

        assertFalse(store.remove("missing"));
        assertFalse(store.remove("  "));

        assertEquals(before, Files.readString(file));
    }

    @Test
    void removingLastModelKeepsAValidEmptyOwnerOnlyCatalogue() throws Exception {
        Path file = tempDir.resolve("model.json");
        CustomModelJsonStore store = new CustomModelJsonStore(file);
        store.save(new CustomModelConfig("only", ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", "secret", Map.of()));

        assertTrue(store.remove("only"));

        assertTrue(Files.exists(file));
        assertTrue(store.list().isEmpty());
        var document = JsonUtils.getMapper().readTree(file.toFile());
        assertEquals(1, document.path("version").asInt());
        assertTrue(document.path("models").isArray());
        assertEquals(0, document.path("models").size());
        try {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file));
        } catch (UnsupportedOperationException _) {
            // Windows/non-POSIX filesystems cannot expose POSIX modes.
        }
    }

    @Test
    void removeFromMalformedFileFailsClosedWithoutDestroyingIt() throws Exception {
        Path file = tempDir.resolve("model.json");
        Files.writeString(file, "{broken");
        CustomModelJsonStore store = new CustomModelJsonStore(file);

        assertThrows(CustomModelConfigException.class, () -> store.remove("anything"));
        assertEquals("{broken", Files.readString(file));
    }

    @Test
    void malformedFileFailsClosedWithoutDestroyingIt() throws Exception {
        Path file = tempDir.resolve("model.json");
        Files.writeString(file, "{broken");
        CustomModelJsonStore store = new CustomModelJsonStore(file);

        assertThrows(CustomModelConfigException.class, store::list);
        assertEquals("{broken", Files.readString(file));
    }

    @Test
    void usesOwnerOnlyPermissionsWherePosixIsAvailable() throws Exception {
        Path file = tempDir.resolve("model.json");
        new CustomModelJsonStore(file).save(new CustomModelConfig(
            "claude-custom", ModelApiProtocol.ANTHROPIC,
            "https://api.anthropic.com/v1", "secret", Map.of()));

        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
        } catch (UnsupportedOperationException _) {
            // Windows/non-POSIX filesystems cannot expose POSIX modes.
        }
    }
}
