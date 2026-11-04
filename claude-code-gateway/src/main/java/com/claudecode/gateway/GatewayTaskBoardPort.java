package com.claudecode.gateway;

import com.claudecode.runtime.tasks.TaskBoardPort;
import java.util.Optional;

/**
 * Consumer-owned boundary for reading one session's current to-do list — the
 * transport the webui to-do dock cold-loads through, alongside the live
 * {@code task_board} mirror frame.
 *
 * <p>Addressing mirrors {@link GatewayPermissionModePort} and
 * {@link GatewaySessionCancelPort}: a blank session id means the active TUI
 * session. The port answers with the runtime's own {@link TaskBoardPort.Snapshot}
 * rather than a gateway-local view, so the gateway never re-models the board;
 * it only projects.
 *
 * <p>The board is owned by the tool layer ({@code TaskToolProvider} owns the
 * concrete service and the runtime port it exposes is a non-owning view).
 * Implementations therefore only read: subscribing, closing, or otherwise
 * mutating the board's lifecycle from this boundary would violate that
 * ownership.
 */
@FunctionalInterface
public interface GatewayTaskBoardPort {

    /**
     * The addressed session's current board snapshot.
     *
     * @param sessionId the session to read, or {@code null} for the active one
     * @return the snapshot, or empty when no board is bound to that session
     */
    Optional<TaskBoardPort.Snapshot> snapshot(String sessionId);
}