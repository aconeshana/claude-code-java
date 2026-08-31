package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.runtime.query.FastModeController;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FastModeToggleActionTest {

    @Test
    void enablingSwitchesUnsupportedModelAndActivatesSharedController() {
        FastModeController controller = new FastModeController(true, false, () -> 0L);
        AtomicReference<String> selectedModel = new AtomicReference<>("sonnet");

        FastModeToggleAction.Result result = FastModeToggleAction.toggle(
            controller, selectedModel.get(), selectedModel::set);

        assertTrue(result.accepted());
        assertTrue(result.enabled());
        assertEquals("opus", result.model());
        assertEquals("opus", selectedModel.get());
        assertTrue(controller.isFastRequest("opus"));
    }

    @Test
    void disablingLeavesTheCurrentModelSelected() {
        FastModeController controller = new FastModeController(true, true, () -> 0L);
        AtomicReference<String> selectedModel = new AtomicReference<>("opus");

        FastModeToggleAction.Result result = FastModeToggleAction.toggle(
            controller, selectedModel.get(), selectedModel::set);

        assertTrue(result.accepted());
        assertFalse(result.enabled());
        assertEquals("opus", result.model());
        assertEquals("opus", selectedModel.get());
        assertFalse(controller.isFastRequest("opus"));
    }

    @Test
    void unavailableControllerRejectsEnableWithoutChangingModel() {
        FastModeController controller = new FastModeController(false, false, () -> 0L);
        AtomicReference<String> selectedModel = new AtomicReference<>("sonnet");

        FastModeToggleAction.Result result = FastModeToggleAction.toggle(
            controller, selectedModel.get(), selectedModel::set);

        assertFalse(result.accepted());
        assertFalse(result.enabled());
        assertEquals("sonnet", result.model());
        assertEquals("sonnet", selectedModel.get());
    }
}
