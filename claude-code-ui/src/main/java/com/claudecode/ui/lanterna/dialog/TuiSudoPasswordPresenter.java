package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionEndpoint;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.interaction.InteractionPresenter;
import com.claudecode.runtime.interaction.InteractionRequest;
import com.claudecode.runtime.interaction.InteractionSupport;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import java.util.Objects;
import java.util.function.Function;

/** Local adapter for the sudo password interaction. */
@Explanation("Presents local sudo password requests in Lanterna")
public final class TuiSudoPasswordPresenter implements InteractionPresenter<
        SudoPasswordInteraction.Request, SudoPasswordInteraction.Result> {
    private final InteractionCoordinator coordinator;
    private final Function<SudoPasswordInteraction.Request,
        SudoPasswordInteraction.Result> prompt;

    public TuiSudoPasswordPresenter(
            InteractionCoordinator coordinator,
            Function<SudoPasswordInteraction.Request,
                SudoPasswordInteraction.Result> prompt) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
    }

    @Override public InteractionEndpoint endpoint() {
        return InteractionEndpoint.LOCAL;
    }

    @Override public InteractionSupport support() {
        return InteractionSupport.SUPPORTED;
    }

    @Override public boolean available(String sessionId) {
        return true;
    }

    @Override public void present(InteractionRequest<SudoPasswordInteraction.Request,
            SudoPasswordInteraction.Result> request) {
        SudoPasswordInteraction.Result result = prompt.apply(request.payload());
        coordinator.respond(InteractionFeatures.SUDO_PASSWORD,
            request.descriptor().id(), request.descriptor().sessionId(), result, endpoint());
    }
}
