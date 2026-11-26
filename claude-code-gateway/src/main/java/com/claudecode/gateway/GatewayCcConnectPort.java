package com.claudecode.gateway;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Consumer-owned boundary for managing the bundled {@code cc-connect} IM
 * collaboration sidecars from the standalone Web gateway daemon.
 */
public interface GatewayCcConnectPort {

    /** Live interactive TUI process discovered by the gateway's worker pool. */
    record LiveWorker(
            long pid,
            String socket,
            String token,
            String workDir,
            String version,
            String sessionId,
            String sessionTitle) {}

    /** Collaboration state reported by a live TUI over Session Link. */
    record CollaborationState(
            String sessionId,
            String channel,
            boolean enabled,
            List<String> availableChannels) {
        public CollaborationState {
            sessionId = sessionId == null ? "" : sessionId;
            channel = channel == null ? "" : channel;
            availableChannels = availableChannels == null ? List.of() : List.copyOf(availableChannels);
        }
    }

    /** Gateway-supplied bridge allowing the CLI manager to query/control live TUIs and emit SSE. */
    interface RemoteBridge {
        List<LiveWorker> liveWorkers();

        Optional<CollaborationState> getCollaboration(long pid, String sessionId);

        Optional<CollaborationState> setCollaboration(
                long pid, String sessionId, String channel, boolean enabled);

        void publishChanged();
    }

    /** Status of one online TUI process from the CC Connector perspective. */
    record TargetStatus(
            long pid,
            String workDir,
            String sessionId,
            String sessionTitle,
            boolean attached,
            String channel,
            String status,
            String startedAt,
            String lastError) {
        public TargetStatus {
            workDir = workDir == null ? "" : workDir;
            sessionId = sessionId == null ? "" : sessionId;
            sessionTitle = sessionTitle == null ? "" : sessionTitle;
            channel = channel == null ? "" : channel;
            status = status == null ? "stopped" : status;
            startedAt = startedAt == null ? "" : startedAt;
            lastError = lastError == null ? "" : lastError;
        }
    }

    /** Full CC Connector status snapshot returned by {@code GET /api/cc-connect}. */
    record Snapshot(
            boolean supported,
            boolean configured,
            boolean setupPending,
            String configPath,
            List<String> availableChannels,
            boolean setupInProgress,
            List<TargetStatus> targets) {
        public Snapshot {
            configPath = configPath == null ? "" : configPath;
            availableChannels = availableChannels == null ? List.of() : List.copyOf(availableChannels);
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    /** Request to attach CC Connector to one online TUI process. */
    record AttachRequest(long pid, String sessionId, String channel, String origin) {}

    /** Request to detach CC Connector from one online TUI process. */
    record DetachRequest(long pid, String sessionId) {}

    /** Request to run the Feishu onboarding wizard inside the Web daemon. */
    record SetupRequest(String mode, String appId, char[] appSecret, String workDir) {
        public SetupRequest {
            appSecret = appSecret == null ? new char[0] : appSecret.clone();
        }

        @Override
        public char[] appSecret() {
            return appSecret.clone();
        }

        public void clearAppSecret() {
            Arrays.fill(appSecret, '\0');
        }
    }

    /** Final outcome of a Feishu onboarding run. */
    record SetupResult(boolean ok, String channel, String message) {}

    /** Recent log lines captured from one TUI's managed sidecar. */
    record LogsResponse(long pid, List<String> lines) {
        public LogsResponse {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    default void bindRemoteBridge(RemoteBridge bridge) {}

    /** Notifies the manager that an online TUI worker disconnected from Session Link. */
    default void onWorkerDisconnected(long pid) {}

    default Snapshot snapshot() {
        return new Snapshot(false, false, false, "", List.of(), false, List.of());
    }

    default Snapshot attach(AttachRequest request) {
        throw new UnsupportedOperationException("CC Connector is not available in this gateway");
    }

    default Snapshot detach(DetachRequest request) {
        throw new UnsupportedOperationException("CC Connector is not available in this gateway");
    }

    default LogsResponse logs(long pid, int tail) {
        return new LogsResponse(pid, List.of());
    }

    default SetupResult setup(SetupRequest request, Consumer<String> progress) {
        throw new UnsupportedOperationException("CC Connector setup is not available in this gateway");
    }

    default boolean cancelSetup() {
        return false;
    }
}
