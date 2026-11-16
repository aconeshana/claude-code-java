package com.claudecode.gateway;

import com.claudecode.runtime.sessionhost.SessionHostEffortState;
import com.claudecode.runtime.sessionhost.SessionHostModelState;
import java.util.List;

/**
 * Projects a hosting process's Session Link answers onto the gateway's own
 * selection records.
 *
 * <p>The link speaks the session-host wire shape ({@code models[].alias},
 * {@code efforts[]} as bare strings); the gateway serves the composer's
 * shape. One place does the conversion so the {@code /api/session/context}
 * handler and the remote-aware port cannot drift into two projections of the
 * same answer.
 */
final class RemoteSelections {

    private RemoteSelections() {
    }

    /**
     * A model answer plus the effort state that rides with it. The host's
     * model and effort are one selection, so both halves of the served body
     * come from the same projection; a null effort (a host that advertises
     * no levels) leaves the effort half blank rather than inventing one.
     */
    static GatewaySessionContextPort.ModelSelection modelSelection(
            SessionHostModelState state, GatewaySessionContextPort.EffortSelection effort) {
        return new GatewaySessionContextPort.ModelSelection(state.current(),
            state.models().stream()
                .map(option -> new GatewaySessionContextPort.ModelChoice(option.name(),
                    option.label(), option.description(), option.defaultOption()))
                .toList(),
            effort == null ? "" : effort.current(),
            effort == null ? "" : effort.effective(),
            effort == null ? List.of() : effort.choices());
    }

    /** The effort half of a host answer. */
    static GatewaySessionContextPort.EffortSelection remoteEffort(SessionHostEffortState state) {
        return new GatewaySessionContextPort.EffortSelection(
            state.current(), state.effective(), state.efforts());
    }
}
