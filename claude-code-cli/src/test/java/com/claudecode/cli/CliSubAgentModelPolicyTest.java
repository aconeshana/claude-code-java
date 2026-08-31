package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.api.ApiConfig;
import com.claudecode.api.CustomModelJsonStore;
import com.claudecode.core.agent.AgentSource;
import com.claudecode.core.agent.BuiltInAgentDefinitions.AgentDefinition;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.tools.agent.SubAgentModelPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.lang3.Strings;

class CliSubAgentModelPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void allowlistRejectionInheritsCallableParent() {
        var policy = policy(gatewayCatalog(), model -> !Strings.CS.equals("haiku", model));

        var decision = policy.resolve("haiku", "sonnet");

        assertEquals(SubAgentModelPolicy.Outcome.INHERIT_PARENT, decision.outcome());
        assertEquals(ModelCatalog.resolve("sonnet"), decision.model());
    }

    @Test
    void capabilityFailureRejectsInsteadOfFallingBack() {
        var unavailable = new ModelAvailability(ApiConfig.ApiProvider.ANTHROPIC, null,
            new ConfigLoader.Credentials(null, null), emptyCatalog());
        var policy = new CliSubAgentModelPolicy(unavailable, _ -> true);

        assertEquals(SubAgentModelPolicy.Outcome.REJECT,
            policy.resolve("haiku", "sonnet").outcome());
    }

    @Test
    void customOnlyParentCanBeInheritedAndAdvertised() {
        CustomModelJsonStore catalog = emptyCatalog();
        catalog.save(new CustomModelConfig("gateway-main", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of()));
        var availability = new ModelAvailability(ApiConfig.ApiProvider.ANTHROPIC, null,
            new ConfigLoader.Credentials(null, null), catalog);
        var policy = new CliSubAgentModelPolicy(availability, _ -> true);

        assertEquals(SubAgentModelPolicy.Outcome.USE_REQUESTED,
            policy.resolve("inherit", "gateway-main").outcome());
        assertEquals("gateway-main", policy.resolve("inherit", "gateway-main").model());
        assertEquals(List.of("gateway-main"), policy.advertisedModels());
    }

    @Test
    void builtInExploreFallsBackToCallableParentWhenDefaultHaikuIsUnavailable() {
        CustomModelJsonStore catalog = emptyCatalog();
        catalog.save(new CustomModelConfig("gateway-main", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of()));
        var availability = new ModelAvailability(ApiConfig.ApiProvider.ANTHROPIC, null,
            new ConfigLoader.Credentials(null, null), catalog);
        var policy = new CliSubAgentModelPolicy(availability, _ -> true);
        AgentDefinition explore = AgentDefinition.builder("Explore", "Explore code")
            .source(AgentSource.BUILT_IN)
            .model("haiku")
            .build();

        var decision = policy.resolveAgent(explore, null, "gateway-main");

        assertEquals(SubAgentModelPolicy.Outcome.INHERIT_PARENT, decision.outcome());
        assertEquals("gateway-main", decision.model());
    }

    @Test
    void explicitUnavailableExploreModelIsRejectedWithoutFallback() {
        CustomModelJsonStore catalog = emptyCatalog();
        catalog.save(new CustomModelConfig("gateway-main", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of()));
        var availability = new ModelAvailability(ApiConfig.ApiProvider.ANTHROPIC, null,
            new ConfigLoader.Credentials(null, null), catalog);
        var policy = new CliSubAgentModelPolicy(availability, _ -> true);
        AgentDefinition explore = AgentDefinition.builder("Explore", "Explore code")
            .source(AgentSource.BUILT_IN)
            .model("haiku")
            .build();

        assertEquals(SubAgentModelPolicy.Outcome.REJECT,
            policy.resolveAgent(explore, "haiku", "gateway-main").outcome());
    }

    @Test
    void globalSubagentModelOverridePrecedesToolSpecifiedModel() {
        CustomModelJsonStore catalog = emptyCatalog();
        catalog.save(new CustomModelConfig("global-model", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of()));
        catalog.save(new CustomModelConfig("tool-model", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of()));
        var availability = new ModelAvailability(ApiConfig.ApiProvider.ANTHROPIC, null,
            new ConfigLoader.Credentials(null, null), catalog);
        var policy = new CliSubAgentModelPolicy(availability, _ -> true);
        AgentDefinition explore = AgentDefinition.builder("Explore", "Explore code")
            .source(AgentSource.BUILT_IN)
            .model("haiku")
            .build();
        SubprocessEnvironment.updateSettings(Map.of(
            "CLAUDE_CODE_SUBAGENT_MODEL", "global-model"));
        try {
            assertEquals("global-model",
                policy.resolveAgent(explore, "tool-model", "tool-model").model());
        } finally {
            SubprocessEnvironment.clearSettings();
        }
    }

    @Test
    void gpt56AliasesResolveForRequestedAndInheritedSubagents() {
        CustomModelJsonStore catalog = emptyCatalog();
        catalog.save(new CustomModelConfig("gpt-5.6-sol", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of()));
        catalog.save(new CustomModelConfig("gpt-5.6-luna", ModelApiProtocol.OPENAI_RESPONSES,
            "https://models.example/v1", null, Map.of()));
        var availability = new ModelAvailability(ApiConfig.ApiProvider.ANTHROPIC, null,
            new ConfigLoader.Credentials(null, null), catalog);
        var policy = new CliSubAgentModelPolicy(availability, _ -> true);

        assertEquals("gpt-5.6-sol", policy.resolve(" SOL ", "luna").model());
        assertEquals("gpt-5.6-luna", policy.resolve("inherit", "luna").model());
    }

    private CliSubAgentModelPolicy policy(CustomModelJsonStore catalog,
                                           Predicate<String> allowlist) {
        var availability = new ModelAvailability(ApiConfig.ApiProvider.ANTHROPIC,
            "https://gateway.example/v1", new ConfigLoader.Credentials(null, "token"), catalog);
        return new CliSubAgentModelPolicy(availability, allowlist);
    }

    private CustomModelJsonStore gatewayCatalog() {
        return emptyCatalog();
    }

    private CustomModelJsonStore emptyCatalog() {
        return new CustomModelJsonStore(tempDir.resolve("model.json"));
    }
}
