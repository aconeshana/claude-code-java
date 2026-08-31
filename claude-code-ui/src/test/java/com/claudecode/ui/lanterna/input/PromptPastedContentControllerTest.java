package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.PastedContent;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptPastedContentControllerTest {

    @Test
    void restoreAdvancesIdsAndSnapshotsDefensively() {
        PromptPastedContentController controller = new PromptPastedContentController();
        controller.restore(Map.of(7, PastedContent.text(7, "old")));

        assertEquals(8, controller.nextId());
        assertEquals("old", controller.snapshot().get(7).content());
    }

    @Test
    void lazySpaceIsConsumedByExactlyOneKey() {
        PromptPastedContentController controller = new PromptPastedContentController();
        assertEquals("", controller.prefixBeforeChipAndArm(true));
        assertTrue(controller.consumeLazySpace(true));
        assertFalse(controller.consumeLazySpace(true));

        controller.prefixBeforeChipAndArm(true);
        assertFalse(controller.consumeLazySpace(false));
        assertFalse(controller.consumeLazySpace(true));
    }

    @Test
    void consecutiveImageChipsAreSeparated() {
        PromptPastedContentController controller = new PromptPastedContentController();
        assertEquals("", controller.prefixBeforeChipAndArm(true));
        assertEquals(" ", controller.prefixBeforeChipAndArm(true));
    }
}
