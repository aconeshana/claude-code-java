package com.claudecode.ui.lanterna.suggest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression coverage for the allocation-free, single-component dropdown. */
class SuggestionPanelTest {

    @Test
    void snapshotControlsHeightAndVisibilityWithoutChildComponents() {
        SuggestionPanel panel = new SuggestionPanel();

        assertFalse(panel.isVisible());
        assertEquals(0, panel.getPreferredSize().getRows());

        panel.setSuggestions(List.of(
            new SuggestionPanel.Suggestion("/config", "Open config"),
            new SuggestionPanel.Suggestion("/model", "Select model")), 120, 24);

        assertTrue(panel.isVisible());
        assertEquals(2, panel.getPreferredSize().getRows());

        panel.hide();
        assertFalse(panel.isVisible());
        assertEquals(0, panel.getPreferredSize().getRows());
    }

    @Test
    void navigationAndAcceptKeepExistingBehavior() {
        SuggestionPanel panel = new SuggestionPanel();
        AtomicReference<SuggestionPanel.Suggestion> accepted = new AtomicReference<>();
        panel.setOnAccept(accepted::set);
        panel.setSuggestions(List.of(
            new SuggestionPanel.Suggestion("/config", "Open config"),
            new SuggestionPanel.Suggestion("/model", "Select model")), 100);

        panel.moveDown();
        assertEquals("/model", panel.peekSelected().primary());
        assertEquals("/model", panel.acceptSelected().primary());
        assertEquals("/model", accepted.get().primary());
        assertFalse(panel.isVisible());
    }

    @Test
    void keepsAllSuggestionsNavigableWhileRenderingOnlyTheViewport() {
        SuggestionPanel panel = new SuggestionPanel();
        List<SuggestionPanel.Suggestion> suggestions = IntStream.range(0, 20)
            .mapToObj(i -> new SuggestionPanel.Suggestion("/command-" + i, "Command " + i))
            .toList();

        panel.setSuggestions(suggestions, 100);

        assertEquals(SuggestionPanel.MAX_VISIBLE, panel.getPreferredSize().getRows());
        for (int i = 0; i < 19; i++) panel.moveDown();
        assertEquals("/command-19", panel.peekSelected().primary(),
            "results after the visible-row limit must remain reachable");
        assertTrue(Arrays.stream(panel.renderedRows(100))
            .anyMatch(row -> Strings.CS.contains(row, "/command-19")),
            "the viewport must follow the selected result");
    }

    @Test
    void commandRowsMatchOfficialTwoColumnInsetWithoutReservedIconGap() {
        SuggestionPanel panel = new SuggestionPanel();
        panel.setSuggestions(List.of(
            new SuggestionPanel.Suggestion("* /agent-reach", "Use the internet"),
            new SuggestionPanel.Suggestion("/plan", "Restate requirements")), 100, 24);

        String[] rows = panel.renderedRows(100);

        assertTrue(Strings.CS.startsWith(rows[0], "  * /agent-reach"));
        assertFalse(Strings.CS.startsWith(rows[0], "    * /agent-reach"));
        assertEquals("Use the internet", rows[0].substring(2 + 24));
        assertEquals("Restate requirements", rows[1].substring(2 + 24));
    }

    @Test
    void unifiedRowsKeepIconAfterTheSameTwoColumnInset() {
        SuggestionPanel panel = new SuggestionPanel();
        panel.setSuggestions(List.of(
            new SuggestionPanel.Suggestion("src/Main.java", "Java source", "+")), 80);

        assertTrue(Strings.CS.startsWith(
            panel.renderedRows(80)[0], "  + src/Main.java – Java source"));
    }

    @Test
    void directoryPathRowsUseTheAvailableWidthInsteadOfTheCommandNameColumn(
            @TempDir Path tempDir) throws Exception {
        String fileName = "a".repeat(80) + ".png";
        Files.createFile(tempDir.resolve(fileName));
        SuggestionPanel.Suggestion path = new DirectorySuggestionService()
            .build(tempDir.resolve("a".repeat(70)).toString())
            .getFirst();
        SuggestionPanel panel = new SuggestionPanel();
        panel.setSuggestions(List.of(path), 240);

        String rendered = panel.renderedRows(240)[0];

        assertTrue(Strings.CS.contains(rendered, path.primary()),
            "path suggestions should not inherit the 40% command-name width cap");
    }
}
