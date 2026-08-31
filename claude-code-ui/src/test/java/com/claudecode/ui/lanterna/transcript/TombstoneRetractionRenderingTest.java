package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/**
 * A tombstone withdraws a row the transcript has already painted, so the retry
 * can answer over clean ground instead of stacking a second reply under the
 * abandoned one.
 *
 * <ul>
 *   <li>the {@code onTombstone} handler that
 *       answers a {@code {type: "tombstone"}} frame by removing the row it
 *       names from the rendered conversation.</li>
 * </ul>
 */
class TombstoneRetractionRenderingTest {

    private static SDKMessage.Assistant assistant(String uuid, String text) {
        return new SDKMessage.Assistant(
            new AssistantMessage(uuid, AssistantContent.of(List.of(new TextBlock(text)))),
            new Usage(10, 5, 0, 0),
            "primary-model");
    }

    private static String transcript(MessagePanel panel) {
        return panel.displayRowsForTest(100).stream()
            .map(MessagePanel.StyledLine::text)
            .reduce("", (left, right) -> left + "\n" + right);
    }

    @Test
    void aTombstoneRemovesTheAssistantRowItNames() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        String uuid = UUID.randomUUID().toString();

        dispatcher.dispatch(assistant(uuid, "Half an answer"), panel);
        assertTrue(Strings.CS.contains(transcript(panel), "Half an answer"),
            "precondition: the abandoned answer was painted");

        dispatcher.dispatch(new SDKMessage.Tombstone(uuid), panel);

        assertFalse(Strings.CS.contains(transcript(panel), "Half an answer"),
            "the withdrawn row must be gone: " + transcript(panel));
    }

    @Test
    void aTombstoneKeepsEverythingRenderedBeforeTheRowItWithdraws() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        String kept = UUID.randomUUID().toString();
        String withdrawn = UUID.randomUUID().toString();

        dispatcher.dispatch(assistant(kept, "An earlier turn"), panel);
        dispatcher.dispatch(assistant(withdrawn, "Half an answer"), panel);
        dispatcher.dispatch(new SDKMessage.Tombstone(withdrawn), panel);

        String text = transcript(panel);
        assertTrue(Strings.CS.contains(text, "An earlier turn"),
            "rows painted before the retraction point survive: " + text);
        assertFalse(Strings.CS.contains(text, "Half an answer"), text);
    }

    @Test
    void theSweepThatFollowsTheFirstTombstoneIsHarmless() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();
        String kept = UUID.randomUUID().toString();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();

        dispatcher.dispatch(assistant(kept, "An earlier turn"), panel);
        int survivingRows = panel.snapshotLineCount();
        dispatcher.dispatch(assistant(first, "First abandoned block"), panel);
        dispatcher.dispatch(assistant(second, "Second abandoned block"), panel);


        dispatcher.dispatch(new SDKMessage.Tombstone(first), panel);
        dispatcher.dispatch(new SDKMessage.Tombstone(second), panel);

        assertEquals(survivingRows, panel.snapshotLineCount(),
            "a redundant tombstone must not eat into the surviving transcript: "
                + transcript(panel));
        assertTrue(Strings.CS.contains(transcript(panel), "An earlier turn"));
    }

    @Test
    void anUnknownTombstoneLeavesTheTranscriptUntouched() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();

        dispatcher.dispatch(assistant(UUID.randomUUID().toString(), "An earlier turn"), panel);
        int before = panel.snapshotLineCount();

        dispatcher.dispatch(new SDKMessage.Tombstone(UUID.randomUUID().toString()), panel);

        assertEquals(before, panel.snapshotLineCount(),
            "a uuid this panel never rendered names nothing to remove: "
                + transcript(panel));
    }
}
