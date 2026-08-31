package com.claudecode.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.RefusalFallbackDecision.Choice;
import com.claudecode.core.message.RefusalFallbackDecision.Host;
import com.claudecode.core.message.RefusalFallbackDecision.Suppression;
import org.junit.jupiter.api.Test;

/**
 * The refusal dialog is the exception, not the rule.
 */
class RefusalFallbackDecisionTest {

    /** An interactive main-thread host that offers a dialog and declared the kind. */
    private static Host.Builder interactiveHost() {
        return new Host.Builder()
            .mainThread(true)
            .dialogHostAvailable(true)
            .consumerLacksDialogCapability(false)
            .switchModelsOnFlag(false);
    }

    @Test
    void aSubagentIsNeverAskedBecauseItHasNobodyToAsk() {
        Host host = interactiveHost().mainThread(false).build();

        assertEquals(Suppression.SUBAGENT, RefusalFallbackDecision.suppression(host));
        assertEquals(Choice.RETRY_FALLBACK, RefusalFallbackDecision.choiceWithoutDialog(
            RefusalFallbackDecision.suppression(host)));
    }

    @Test
    void aHostWithoutADialogPortIsSuppressedBeforeTheSettingIsConsulted() {
        Host host = interactiveHost().dialogHostAvailable(false).build();

        assertEquals(Suppression.NO_DIALOG_HOST, RefusalFallbackDecision.suppression(host));
    }

    @Test
    void theShippedSettingSwitchesModelsWithoutAsking() {
        Host host = interactiveHost().switchModelsOnFlag(true).build();

        assertEquals(Suppression.SETTING, RefusalFallbackDecision.suppression(host));
        assertEquals(Choice.RETRY_FALLBACK, RefusalFallbackDecision.choiceWithoutDialog(
            RefusalFallbackDecision.suppression(host)));
    }

    /**
     * The setting is consulted <em>before</em> the consumer's declared dialog
     * kinds. Swapping the two would turn the default session's automatic retry
     * into a cancelled turn for every consumer that never declared the kind.
     */
    @Test
    void theSettingOutranksAConsumerThatNeverDeclaredTheDialogKind() {
        Host host = interactiveHost()
            .switchModelsOnFlag(true)
            .consumerLacksDialogCapability(true)
            .build();

        assertEquals(Suppression.SETTING, RefusalFallbackDecision.suppression(host));
        assertEquals(Choice.RETRY_FALLBACK, RefusalFallbackDecision.choiceWithoutDialog(
            RefusalFallbackDecision.suppression(host)));
    }

    /** The only suppression state that declines the fallback instead of taking it. */
    @Test
    void aConsumerMissingTheDialogKindIsTheSoleDecliningState() {
        Host host = interactiveHost().consumerLacksDialogCapability(true).build();

        assertEquals(Suppression.NO_CONSUMER_CAPABILITY,
            RefusalFallbackDecision.suppression(host));
        assertEquals(Choice.CANCELLED, RefusalFallbackDecision.choiceWithoutDialog(
            RefusalFallbackDecision.suppression(host)));
    }

    @Test
    void theDialogIsShownOnlyWhenNothingSuppressesIt() {
        assertNull(RefusalFallbackDecision.suppression(interactiveHost().build()));
    }

    /**
     * With no suppression the call site still seeds the choice with
     * {@code retry_fallback} before awaiting the dialog, so a dialog that never
     * answers degrades to the automatic retry rather than to a cancelled turn.
     */
    @Test
    void theSeedChoiceBeforeTheDialogAnswersIsTheAutomaticRetry() {
        assertEquals(Choice.RETRY_FALLBACK, RefusalFallbackDecision.choiceWithoutDialog(null));
    }

    @Test
    void theServerLaneStaysUnarmedOnlyWhenTheUserTurnedTheSettingOff() {
        assertTrue(RefusalFallbackDecision.suppressesServerLane(
            interactiveHost().dialogHostAvailable(false).build()));
        assertTrue(RefusalFallbackDecision.suppressesServerLane(
            interactiveHost().consumerLacksDialogCapability(true).build()));

        assertFalse(RefusalFallbackDecision.suppressesServerLane(
                interactiveHost().dialogHostAvailable(false).switchModelsOnFlag(true).build()),
            "the shipped setting leaves the server lane armed");
        assertFalse(RefusalFallbackDecision.suppressesServerLane(interactiveHost().build()),
            "a host that can ask keeps the server lane armed");
        assertFalse(RefusalFallbackDecision.suppressesServerLane(
                interactiveHost().mainThread(false).dialogHostAvailable(false).build()),
            "a subagent never arms or disarms the server lane on its own");
    }
}
