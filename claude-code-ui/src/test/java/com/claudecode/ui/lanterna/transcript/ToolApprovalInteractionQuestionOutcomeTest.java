package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.ui.lanterna.dialog.PermissionDialog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Where an {@code AskUserQuestion} card's three outcomes land on the permission callback.
 *
 * <p>Submit rewrites the input and allows; {@code Chat about this} denies <em>with</em> feedback so
 * the model reformulates instead of the turn aborting ({@code k2g}); escape denies bare.
 */
class ToolApprovalInteractionQuestionOutcomeTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ObjectNode designQuestionInput() {
        ObjectNode input = M.createObjectNode();
        ArrayNode questions = input.putArray("questions");
        ObjectNode question = questions.addObject();
        question.put("question", "Which approach?");
        question.put("header", "Approach");
        question.put("multiSelect", false);
        ArrayNode options = question.putArray("options");
        ObjectNode tabs = options.addObject();
        tabs.put("label", "Tabs");
        tabs.put("description", "keeps state");
        tabs.put("preview", "# Tabs");
        ObjectNode drawer = options.addObject();
        drawer.put("label", "Drawer");
        drawer.put("description", "hides state");
        return input;
    }

    /** Drives {@code resolveQuestion} on a virtual thread against a headless GUI. */
    private final class Run {
        final ToolApprovalInteraction interaction;
        final CompletableFuture<PermissionAskCallback.Result> result = new CompletableFuture<>();
        final AtomicBoolean closed = new AtomicBoolean();

        Run() throws Exception {
            var screen = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(100, 40)));
            screen.startScreen();
            var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
            interaction = new ToolApprovalInteraction(
                gui, null, null, null, null, null, () -> false, _ -> {});
            var context = PermissionAskContext.simple(
                "AskUserQuestion", designQuestionInput(), "tool-1");
            Thread.ofVirtual().start(() -> result.complete(interaction.resolveQuestion(
                context, new PermissionDialog(), () -> closed.set(true), () -> false)));
            long deadline = System.currentTimeMillis() + 2000;
            while (!interaction.questionView().isActive()
                    && System.currentTimeMillis() < deadline) {
                gui.getGUIThread().processEventsAndUpdate();
                Thread.sleep(5);
            }
            assertTrue(interaction.questionView().isActive(), "the question card must mount");
        }

        void key(KeyType type) {
            interaction.questionView().handleKey(new KeyStroke(type), new AtomicBoolean(true));
        }

        PermissionAskCallback.Result await() throws Exception {
            return result.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void submittingAnswersAllowsWithTheRewrittenInput() throws Exception {
        Run run = new Run();
        run.key(KeyType.ENTER);

        PermissionAskCallback.Result result = run.await();

        assertTrue(result.allowed());
        assertNull(result.feedback());
        assertEquals("Tabs", result.updatedInput().at("/answers/Which approach?").asText());
        assertEquals("# Tabs",
            result.updatedInput().at("/annotations/Which approach?/preview").asText());
        assertTrue(run.closed.get(), "the prompt UI must be restored either way");
    }

    @Test
    void chatAboutThisDeniesWithTheClarificationFeedback() throws Exception {
        Run run = new Run();
        run.key(KeyType.ARROW_DOWN);        // Drawer
        run.key(KeyType.ARROW_DOWN);        // Chat about this
        run.key(KeyType.ENTER);

        PermissionAskCallback.Result result = run.await();

        assertFalse(result.allowed());
        assertFalse(result.directDenial(),
            "a clarification must carry the reject-with-reason prefix, not replace it");
        assertNull(result.updatedInput());
        assertTrue(result.feedback().startsWith("The user wants to clarify these questions."),
            result.feedback());
        assertTrue(result.feedback().contains("- \"Which approach?\""), result.feedback());
    }

    @Test
    void escapeDeniesWithoutFeedbackSoTheTurnAborts() throws Exception {
        Run run = new Run();
        run.key(KeyType.ESCAPE);

        PermissionAskCallback.Result result = run.await();

        assertFalse(result.allowed());
        assertNull(result.feedback());
        assertNull(result.updatedInput());
    }
}
