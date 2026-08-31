package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.engine.RefusalFallbackPrompt;
import com.claudecode.core.message.RefusalErrorMessage;
import com.claudecode.core.message.RefusalFallbackDecision;
import com.claudecode.core.message.RefusalFallbackPromptCopy;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code Session paused} dialog a refused turn opens. */
class RefusalFallbackDialogTest {

    private static final String REFUSED = "claude-opus-4-5-20251101";
    private static final String FALLBACK = "claude-sonnet-4-5-20250929";

    private static RefusalFallbackPrompt.Request request(String guidance) {
        return new RefusalFallbackPrompt.Request(REFUSED, FALLBACK, null, guidance, List.of());
    }

    private static RefusalFallbackDialog dialog(boolean hyperlinks) {
        RefusalFallbackDialog dialog = new RefusalFallbackDialog(24);
        dialog.setHyperlinkSupport(() -> hyperlinks);
        return dialog;
    }

    private static void press(RefusalFallbackDialog dialog, KeyType type) {
        dialog.handleKey(new KeyStroke(type), new AtomicBoolean(true));
    }

    private static String plain(List<RefusalFallbackBody.Run> runs) {
        StringBuilder text = new StringBuilder();
        runs.forEach(run -> text.append(run.text()));
        return text.toString();
    }

    @Test
    void isInvisibleUntilPrompted() {
        RefusalFallbackDialog dialog = dialog(false);

        assertFalse(dialog.isActive());
        assertEquals(new TerminalSize(0, 0), dialog.calculatePreferredSize());
        assertNull(dialog.nextFocus(null));
        assertNull(dialog.previousFocus(null));
    }

    @Test
    void takesUpSpaceOncePrompted() {
        RefusalFallbackDialog dialog = dialog(false);

        dialog.prompt(request(null), _ -> { });

        assertTrue(dialog.isActive());
        assertTrue(dialog.calculatePreferredSize().getRows() > 0);
    }

    @Test
    void titleIsTheReleasedWording() {
        RefusalFallbackDialog dialog = dialog(false);

        dialog.prompt(request(null), _ -> { });

        assertEquals("Session paused", dialog.title());
    }

    @Test
    void bodyIsTheSharedPromptCopy() {
        RefusalFallbackDialog dialog = dialog(false);

        dialog.prompt(request(null), _ -> { });

        assertEquals(RefusalFallbackPromptCopy.body(REFUSED, null),
            String.join(" ", dialog.bodyLines().stream()
                .map(RefusalFallbackDialogTest::plain).toList()));
    }

    @Test
    void bodyCarriesTheLearnMoreLinkWhenTheTerminalSupportsIt() {
        RefusalFallbackDialog dialog = dialog(true);

        dialog.prompt(request(null), _ -> { });

        List<RefusalFallbackBody.Run> flattened = dialog.bodyLines().stream()
            .flatMap(List::stream).toList();
        assertTrue(flattened.stream().anyMatch(run ->
                Strings.CS.equals("learn more", run.text())
                    && Strings.CS.equals(RefusalErrorMessage.LEARN_MORE_URL, run.hyperlinkUrl())),
            "no learn-more link: " + flattened);
    }

    @Test
    void optionsAreSwitchThenEditWithSwitchFocused() {
        RefusalFallbackDialog dialog = dialog(false);

        dialog.prompt(request(null), _ -> { });

        assertEquals(List.of(
                "❯ " + RefusalFallbackPromptCopy.switchLabel(FALLBACK),
                "  " + RefusalFallbackPromptCopy.editLabel(REFUSED)),
            dialog.optionLines());
    }

    @Test
    void enterOnTheFirstOptionRetriesOnTheFallbackModel() {
        AtomicReference<RefusalFallbackDecision.Choice> answer = new AtomicReference<>();
        RefusalFallbackDialog dialog = dialog(false);
        dialog.prompt(request(null), answer::set);

        press(dialog, KeyType.ENTER);

        assertEquals(RefusalFallbackDecision.Choice.RETRY_FALLBACK, answer.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void enterOnTheSecondOptionHandsThePromptBack() {
        AtomicReference<RefusalFallbackDecision.Choice> answer = new AtomicReference<>();
        RefusalFallbackDialog dialog = dialog(false);
        dialog.prompt(request(null), answer::set);

        press(dialog, KeyType.ARROW_DOWN);
        press(dialog, KeyType.ENTER);

        assertEquals(RefusalFallbackDecision.Choice.EDIT_PROMPT, answer.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void escapeIsDistinctFromEitherOption() {
        AtomicReference<RefusalFallbackDecision.Choice> answer = new AtomicReference<>();
        RefusalFallbackDialog dialog = dialog(false);
        dialog.prompt(request(null), answer::set);

        press(dialog, KeyType.ESCAPE);

        assertEquals(RefusalFallbackDecision.Choice.CANCELLED, answer.get());
        assertFalse(dialog.isActive());
    }

    @Test
    void guidanceIsOmittedWhenTheProviderNeedsNoSetup() {
        RefusalFallbackDialog dialog = dialog(false);

        dialog.prompt(request(RefusalFallbackPromptCopy.guidance(true)), _ -> { });

        assertEquals(List.of(), dialog.guidanceLines());
    }

    @Test
    void guidanceIsShownForAThirdPartyProvider() {
        RefusalFallbackDialog dialog = dialog(false);
        String guidance = RefusalFallbackPromptCopy.guidance(false);
        RefusalFallbackDialog without = dialog(false);
        without.prompt(request(null), _ -> { });

        dialog.prompt(request(guidance), _ -> { });

        assertEquals(guidance, String.join(" ", dialog.guidanceLines()));
        assertTrue(dialog.contentRows() > without.contentRows(),
            "the guidance block adds rows");
    }

    @Test
    void keysAreIgnoredOnceResolved() {
        AtomicReference<RefusalFallbackDecision.Choice> answer = new AtomicReference<>();
        RefusalFallbackDialog dialog = dialog(false);
        dialog.prompt(request(null), answer::set);
        press(dialog, KeyType.ESCAPE);

        press(dialog, KeyType.ENTER);

        assertEquals(RefusalFallbackDecision.Choice.CANCELLED, answer.get());
    }
}
