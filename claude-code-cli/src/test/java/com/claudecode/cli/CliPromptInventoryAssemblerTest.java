package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.tools.skills.SkillLoader;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

class CliPromptInventoryAssemblerTest {

    @Test
    void modelFacingAgentListingOmitsDefinitionsWhoseModelsAreUnavailable() {
        String listing = CliPromptInventoryAssembler.buildAgentListingMessage(
            new SkillLoader(), "gateway-main",
            agent -> !Strings.CS.equals("haiku", agent.model())
                && !Strings.CS.equals("sonnet", agent.model()));

        assertTrue(Strings.CS.contains(listing, "general-purpose"));
        assertFalse(Strings.CS.contains(listing, "Explore:"));
        assertFalse(Strings.CS.contains(listing, "claude-code-guide:"));
        assertFalse(Strings.CS.contains(listing, "statusline-setup:"));
    }
}
