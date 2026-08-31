package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.api.ApiConfig;
import com.claudecode.api.CustomModelJsonStore;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.model.ModelCatalog;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelAvailabilityTest {

    @TempDir
    Path tempDir;

    @Test
    void directAnthropicBuiltInsRequireResolvedApiKey() {
        var noAuth = availability(null, new ConfigLoader.Credentials(null, null), emptyCatalog());
        var withKey = availability(null,
            new ConfigLoader.Credentials("sk-ant-test", null), emptyCatalog());

        assertFalse(noAuth.showBuiltInModelFamilies());
        assertFalse(noAuth.canCall("haiku"));
        assertTrue(withKey.showBuiltInModelFamilies());
        assertTrue(withKey.canCall("sonnet"));
    }

    @Test
    void customMainAndInheritedAgentRemainCallableWithoutAnthropicKey() {
        CustomModelJsonStore catalog = emptyCatalog();
        catalog.save(new CustomModelConfig("gateway-main", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of()));
        var availability = availability(null,
            new ConfigLoader.Credentials(null, null), catalog);

        assertTrue(availability.canCall("gateway-main"));
        assertTrue(availability.canCall("inherit"));
        assertFalse(availability.canCall("haiku"));
    }

    @Test
    void explicitGatewayRequiresApiKeyOrBearerToken() {
        var noAuth = availability("http://127.0.0.1:43123/v1",
            new ConfigLoader.Credentials(null, null), emptyCatalog());
        var withKey = availability("http://127.0.0.1:43123/v1",
            new ConfigLoader.Credentials("gateway-key", null), emptyCatalog());
        var withBearer = availability("http://127.0.0.1:43123/v1",
            new ConfigLoader.Credentials(null, "gateway-token"), emptyCatalog());

        assertFalse(noAuth.showBuiltInModelFamilies());
        assertFalse(noAuth.canCall("haiku"));
        assertTrue(withKey.showBuiltInModelFamilies());
        assertTrue(withKey.canCall("sonnet"));
        assertTrue(withBearer.showBuiltInModelFamilies());
        assertTrue(withBearer.canCall("opus"));
    }

    @Test
    void directAnthropicBearerOnlyDoesNotExposeBuiltIns() {
        var availability = availability(null,
            new ConfigLoader.Credentials(null, "direct-bearer"), emptyCatalog());

        assertFalse(availability.showBuiltInModelFamilies());
        assertFalse(availability.canCall("sonnet"));
    }

    @Test
    void agentOverrideProjectionUsesOfficialOrderThenSortedCustomModels() {
        CustomModelJsonStore catalog = emptyCatalog();
        catalog.save(new CustomModelConfig("zeta", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of()));
        catalog.save(new CustomModelConfig("alpha", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of()));

        var full = availability("https://gateway.example/v1",
            new ConfigLoader.Credentials(null, "token"), catalog);
        var customOnly = availability(null,
            new ConfigLoader.Credentials(null, null), catalog);

        assertEquals(List.of("sonnet", "opus", "haiku", "fable", "alpha", "zeta"),
            full.agentOverrideModels(_ -> true));
        assertEquals(List.of("alpha", "zeta"),
            customOnly.agentOverrideModels(_ -> true));
        assertEquals(List.of("sonnet", "haiku", "fable", "alpha", "zeta"),
            full.agentOverrideModels(model -> !Strings.CS.equals("opus", model)));
    }

    @Test
    void builtInAliasMappedToCustomModelRemainsCallableWithoutAnthropicKey() {
        CustomModelJsonStore catalog = emptyCatalog();
        catalog.save(new CustomModelConfig("gateway-sonnet", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of()));
        ModelCatalog.installModelOverrideLookup(modelId ->
            Strings.CS.equals(ModelCatalog.LATEST_SONNET, modelId) ? "gateway-sonnet" : null);
        try {
            var availability = availability(null,
                new ConfigLoader.Credentials(null, null), catalog);
            assertTrue(availability.canCall("sonnet"));
            assertFalse(availability.showBuiltInModelFamilies());
        } finally {
            ModelCatalog.installModelOverrideLookup(null);
        }
    }

    @Test
    void gpt56AliasesResolveToCustomModelsWithoutAnthropicKey() {
        CustomModelJsonStore catalog = emptyCatalog();
        catalog.save(new CustomModelConfig("gpt-5.6-sol", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of()));
        catalog.save(new CustomModelConfig("gpt-5.6-luna", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of()));
        var availability = availability(null,
            new ConfigLoader.Credentials(null, null), catalog);

        assertTrue(availability.canCall("sol"));
        assertTrue(availability.canCall("LUNA"));
    }

    private ModelAvailability availability(
            String baseUrl, ConfigLoader.Credentials credentials,
            CustomModelJsonStore catalog) {
        return new ModelAvailability(
            ApiConfig.ApiProvider.ANTHROPIC, baseUrl, credentials, catalog);
    }

    private CustomModelJsonStore emptyCatalog() {
        return new CustomModelJsonStore(tempDir.resolve("model.json"));
    }
}
