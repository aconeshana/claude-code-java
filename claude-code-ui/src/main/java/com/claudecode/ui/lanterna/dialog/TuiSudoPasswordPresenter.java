package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionEndpoint;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.interaction.InteractionPresenter;
import com.claudecode.runtime.interaction.InteractionRequest;
import com.claudecode.runtime.interaction.InteractionResolution;
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
    private final Runnable dismissPrompt;

    public TuiSudoPasswordPresenter(
            InteractionCoordinator coordinator,
            Function<SudoPasswordInteraction.Request,
                SudoPasswordInteraction.Result> prompt) {
        this(coordinator, prompt, () -> {});
    }

    /**
     * @param dismissPrompt closes a prompt that is still mounted when another
     *     endpoint answers the same ask first; a no-op when nothing is open
     */
    public TuiSudoPasswordPresenter(
            InteractionCoordinator coordinator,
            Function<SudoPasswordInteraction.Request,
                SudoPasswordInteraction.Result> prompt,
            Runnable dismissPrompt) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.dismissPrompt = Objects.requireNonNull(dismissPrompt, "dismissPrompt");
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
        // A result that lost the race to the browser is refused here and
        // wiped by the coordinator; an accepted one is written by the sudo run.
        coordinator.respond(InteractionFeatures.SUDO_PASSWORD,
            request.descriptor().id(), request.descriptor().sessionId(), result, endpoint());
    }

    /**
     * Closes the terminal prompt when the web client — or any other endpoint —
     * answered the ask first, so the user is not left typing into a password
     * field whose answer would be thrown away.
     */
    @Override public void resolved(
            InteractionResolution<SudoPasswordInteraction.Result> resolution) {
        if (resolution.origin() == InteractionEndpoint.LOCAL) return;
        dismissPrompt.run();
    }
}
