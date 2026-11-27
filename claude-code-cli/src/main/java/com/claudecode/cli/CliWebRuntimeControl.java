package com.claudecode.cli;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.platform.Platform;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.gateway.GatewayRuntimeControlPort;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.tools.cron.CronStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Host runtime control for {@code claude web}:
 * <ul>
 *   <li><b>Keepalive mode</b>: holds a macOS {@code /usr/bin/caffeinate -i -w <pid>}
 *       idle-sleep assertion bound to the JVM PID (zero orphan processes on exit),
 *       persisted under {@code ~/.claude/web-keepalive.json}.</li>
 *   <li><b>Restart runtime</b>: reloads durable scheduled tasks and the custom
 *       model catalog in place without dropping the HTTP port or auth token.</li>
 * </ul>
 */
final class CliWebRuntimeControl implements GatewayRuntimeControlPort, AutoCloseable {

    private static final Logger log = Logger.getLogger(CliWebRuntimeControl.class.getName());
    private static final Path CAFFEINATE_BIN = Path.of("/usr/bin/caffeinate");
    private static final boolean SUPPORTED = Platform.IS_DARWIN
        && Files.isExecutable(CAFFEINATE_BIN);

    private final Path stateFile;
    private final CliWebCronDaemon cronDaemon;
    private final CustomModelCatalog modelCatalog;
    private final Runnable onReload;
    private boolean enabled;
    private Process caffeinateProcess;

    CliWebRuntimeControl(CliWebCronDaemon cronDaemon, CustomModelCatalog modelCatalog) {
        this(cronDaemon, modelCatalog, null);
    }

    CliWebRuntimeControl(CliWebCronDaemon cronDaemon, CustomModelCatalog modelCatalog,
                         Runnable onReload) {
        this.stateFile = ClaudePaths.CLAUDE_HOME.resolve("web-keepalive.json");
        this.cronDaemon = cronDaemon;
        this.modelCatalog = modelCatalog;
        this.onReload = onReload;
        this.enabled = readPersistedEnabled();
        if (this.enabled) {
            syncCaffeinate(true);
        }
    }

    @Override
    public synchronized KeepaliveState keepalive() {
        boolean active = caffeinateProcess != null && caffeinateProcess.isAlive();
        return new KeepaliveState(enabled, SUPPORTED, active);
    }

    @Override
    public synchronized KeepaliveState setKeepalive(boolean nextEnabled) {
        this.enabled = nextEnabled;
        persistEnabled(nextEnabled);
        syncCaffeinate(nextEnabled);
        return keepalive();
    }

    @Override
    public synchronized RestartResult restartRuntime() {
        if (cronDaemon != null) {
            cronDaemon.reloadNow();
        }
        if (onReload != null) {
            onReload.run();
        }
        int tasks = CronStore.list().size();
        int models = modelCatalog == null ? 0 : modelCatalog.list().size();
        if (enabled) {
            syncCaffeinate(true);
        }
        return new RestartResult(true, tasks, models);
    }

    private void syncCaffeinate(boolean hold) {
        if (!hold || !SUPPORTED) {
            stopCaffeinate();
            return;
        }
        if (caffeinateProcess != null && caffeinateProcess.isAlive()) {
            return;
        }
        try {
            long pid = ProcessHandle.current().pid();
            caffeinateProcess = new ProcessBuilder(
                List.of(CAFFEINATE_BIN.toString(), "-i", "-w", Long.toString(pid)))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        } catch (IOException failure) {
            log.log(Level.WARNING, "Failed to start caffeinate keepalive process", failure);
            caffeinateProcess = null;
        }
    }

    private void stopCaffeinate() {
        if (caffeinateProcess != null) {
            caffeinateProcess.destroy();
            caffeinateProcess = null;
        }
    }

    private boolean readPersistedEnabled() {
        if (!Files.isRegularFile(stateFile)) return false;
        try {
            JsonNode node = JsonUtils.readJson(stateFile);
            return node != null && node.path("enabled").asBoolean(false);
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }

    private void persistEnabled(boolean value) {
        try {
            Files.createDirectories(stateFile.getParent());
            ObjectNode root = JsonUtils.getMapper().createObjectNode();
            root.put("enabled", value);
            Files.writeString(stateFile, JsonUtils.toPrettyJson(root) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException failure) {
            log.log(Level.FINE, "Failed to persist keepalive preference", failure);
        }
    }

    @Override
    public synchronized void close() {
        stopCaffeinate();
    }
}
