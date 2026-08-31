package com.claudecode.ui.lanterna.repl;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.runtime.sessionhost.SessionHostModelOption;
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
            List.of(custom));

        assertEquals("default", options.getFirst().name());
        assertEquals("fable", options.get(1).alias());
        assertEquals("opus", options.get(2).alias());
        assertEquals("sonnet", options.get(3).alias());
        assertEquals("haiku", options.get(4).alias());
        assertTrue(options.stream().anyMatch(option -> Strings.CS.equals("sol", option.name())));
        assertTrue(options.stream().anyMatch(
            option -> Strings.CS.equals("blocked-current", option.name())));
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
}
