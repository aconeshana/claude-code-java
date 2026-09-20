package com.claudecode.runtime.sessionhost;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionHostModelOptionsTest {

    @Test
    void exposesNativeChoicesAliasesCustomModelsAndPolicyBlockedCurrent() {
        CustomModelConfig custom = new CustomModelConfig(
            "sol", ModelApiProtocol.OPENAI_RESPONSES, "https://models.example.test", null, Map.of());

        List<SessionHostModelOption> options = SessionHostModelOptions.build(
            "blocked-current", model -> !Strings.CS.equals("blocked-current", model),
            List.of(custom), true);

        assertEquals("fable", options.getFirst().alias());
        assertEquals("opus", options.get(1).alias());
        assertEquals("sonnet", options.get(2).alias());
        assertEquals("haiku", options.get(3).alias());
        assertTrue(options.stream().anyMatch(option -> Strings.CS.equals("sol", option.name())));
        assertTrue(options.stream().anyMatch(
            option -> Strings.CS.equals("blocked-current", option.name())));
    }

    @Test
    void neverOffersADefaultRecommendedRow() {
        List<SessionHostModelOption> options = SessionHostModelOptions.build(
            null, _ -> true, List.of(), true);

        assertTrue(options.stream().noneMatch(SessionHostModelOption::defaultOption));
        assertTrue(options.stream().noneMatch(
            option -> Strings.CS.equals("default", option.name())));
    }

    @Test
    void nonFirstPartyProjectionStartsWithCustomAndCurrentModelsOnly() {
        CustomModelConfig custom = new CustomModelConfig(
            "gateway", ModelApiProtocol.ANTHROPIC,
            "https://gateway.example.test", null, Map.of());

        List<SessionHostModelOption> options = SessionHostModelOptions.build(
            null, _ -> true, List.of(custom), false);

        assertEquals(List.of("gateway"), options.stream()
            .map(SessionHostModelOption::name).toList());
    }

    /**
     * The regression this guards: with built-ins gated off, a seated built-in
     * model must not reappear through the current-model fallback row — that is
     * how the official families leaked back beside a custom catalogue and
     * rendered the same model twice.
     */
    @Test
    void nonFirstPartyProjectionOmitsABuiltInCurrentModel() {
        CustomModelConfig custom = new CustomModelConfig(
            "anthropic.claude-sonnet-5", ModelApiProtocol.ANTHROPIC,
            "https://gateway.example.test", null, Map.of());

        List<SessionHostModelOption> options = SessionHostModelOptions.build(
            "claude-sonnet-5", _ -> true, List.of(custom), false);

        assertEquals(List.of("anthropic.claude-sonnet-5"), options.stream()
            .map(SessionHostModelOption::name).toList());
    }

    @Test
    void currentSelectionPrefersThePreferenceThenTheResolvedModel() {
        assertEquals("anthropic.claude-sonnet-5", SessionHostModelOptions.currentSelection(
            "anthropic.claude-sonnet-5", "claude-sonnet-5"));
        assertEquals("claude-sonnet-5",
            SessionHostModelOptions.currentSelection(null, "claude-sonnet-5"));
        assertEquals("", SessionHostModelOptions.currentSelection(null, null));
    }

    @Test
    void currentSelectionKeepsOpusPlanVerbatim() {
        assertEquals("opusplan",
            SessionHostModelOptions.currentSelection("opusplan", "claude-opus-5"));
    }

    /**
     * {@code "default"} is no longer a listed choice, but a Session Link client
     * that round-trips an older {@code current} still sends it and must not be
     * rejected.
     */
    @Test
    void isSelectableAcceptsTheLegacyDefaultInput() {
        List<SessionHostModelOption> options = SessionHostModelOptions.build(
            null, _ -> true, List.of(), false);

        assertTrue(SessionHostModelOptions.isSelectable(options, "default"));
        assertFalse(SessionHostModelOptions.isSelectable(options, "no-such-model"));
    }
}
