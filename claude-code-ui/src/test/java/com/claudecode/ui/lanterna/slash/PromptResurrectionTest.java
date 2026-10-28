package com.claudecode.ui.lanterna.slash;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.CommandMetadataEncoder;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The user messages 236's processSlashCommand gives a local / local-jsx command's result. */
class PromptResurrectionTest {

    private static final String BREADCRUMB =
        CommandMetadataEncoder.encodeCommandInputTags("mcp", "reconnect x");

    @Test
    void aLocalCommandResurrectsOnlyItsInputEvenForASystemResult() {
        assertEquals(List.of(BREADCRUMB), PromptResurrection.localResultTexts(
            false, "mcp", "reconnect x", CommandResult.of("Reconnected")));
    }

    @Test
    void aLocalJsxSystemResultResurrectsNothing() {
        assertEquals(List.of(), PromptResurrection.localResultTexts(
            true, "mcp", "reconnect x", CommandResult.rename("n", "Renamed")));
    }

    @Test
    void aLocalJsxUserResultCarriesItsOutputOrNoContent() {
        assertEquals(List.of(BREADCRUMB, "<local-command-stdout>(no content)</local-command-stdout>"),
            PromptResurrection.localResultTexts(
                true, "mcp", "reconnect x", CommandResult.localJsx("")));
    }

    @Test
    void aSkippedResultResurrectsNothing() {
        assertEquals(List.of(), PromptResurrection.localResultTexts(
            false, "mcp", "reconnect x", CommandResult.skip()));
    }
}
