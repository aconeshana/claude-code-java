package com.claudecode.ui.lanterna.repl;

import com.claudecode.runtime.query.FastModeController;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Applies one interactive Fast Mode toggle to the shared query-session state.
 */
final class FastModeToggleAction {

    private FastModeToggleAction() {}

    static Result toggle(FastModeController controller, String currentModel,
                         Consumer<String> modelSetter) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(modelSetter, "modelSetter");

        boolean enable = !controller.enabled();
        if (!controller.setEnabled(enable)) {
            return new Result(false, controller.enabled(), currentModel);
        }

        String selectedModel = currentModel;
        if (enable && !FastModeController.supportsModel(currentModel)) {
            selectedModel = "opus";
            modelSetter.accept(selectedModel);
        }
        return new Result(true, enable, selectedModel);
    }

    record Result(boolean accepted, boolean enabled, String model) {}
}
