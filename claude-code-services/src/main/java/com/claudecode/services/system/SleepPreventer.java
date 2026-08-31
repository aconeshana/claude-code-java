package com.claudecode.services.system;

import com.claudecode.core.platform.Platform;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SleepPreventer — prevents system sleep while Claude is actively working.
 */
public class SleepPreventer {

    private static final Logger LOG = LoggerFactory.getLogger(SleepPreventer.class);


    private static final int CAFFEINATE_TIMEOUT_SECONDS = 300;


    private static final long RESTART_INTERVAL_MINUTES = 4;

    private boolean active = false;
    private Process process;
    private ScheduledExecutorService restartExecutor;

    /** Prevent system from sleeping. */
    public synchronized void preventSleep() {
        if (active) return;
        active = true;

        if (Platform.IS_DARWIN) {
            restartExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "sleep-preventer-restart");
                    t.setDaemon(true);
                    return t;
                });
            restartExecutor.scheduleWithFixedDelay(this::restartCaffeinate,
                RESTART_INTERVAL_MINUTES, RESTART_INTERVAL_MINUTES, TimeUnit.MINUTES);
            Thread.ofVirtual().name("sleep-preventer-start").start(this::spawnIfActive);
        } else if (Platform.IS_LINUX) {
            Thread.ofVirtual().name("sleep-preventer-start").start(this::spawnIfActive);
        } else {
            LOG.debug("Sleep prevention not supported on this platform");
        }
    }

    private synchronized void spawnIfActive() {
        if (!active || (process != null && process.isAlive())) return;
        if (Platform.IS_DARWIN) {
            spawnCaffeinate();
            return;
        }
        if (!Platform.IS_LINUX) return;
        try {
            // systemd-inhibit blocks sleep while running
            process = new ProcessBuilder(
                    "systemd-inhibit", "--what=idle",
                    "--who=claude-code", "--why=Long operation in progress",
                    "sleep", "infinity")
                    .redirectErrorStream(true)
                    .start();
            try { process.getOutputStream().close(); } catch (IOException _) {}
            LOG.info("Sleep prevention started (systemd-inhibit)");
        } catch (Exception e) {
            LOG.warn("Failed to prevent sleep (systemd-inhibit): {}", e.getMessage());
        }
    }

    private synchronized void restartCaffeinate() {
        if (!active) return;
        LOG.debug("Restarting caffeinate to maintain sleep prevention");
        killProcess();
        spawnCaffeinate();
    }

    private void spawnCaffeinate() {
        try {
            // -i: prevent idle sleep (least aggressive — display can still sleep).
            // -t: self-expiry in seconds — self-healing if this JVM is SIGKILLed
// before allowSleep/cleanup can run.
            process = new ProcessBuilder(
                    "caffeinate", "-i", "-t", String.valueOf(CAFFEINATE_TIMEOUT_SECONDS))
                    .redirectErrorStream(true)
                    .start();
            try { process.getOutputStream().close(); } catch (IOException _) {}
            LOG.info("Sleep prevention started (caffeinate)");
        } catch (Exception e) {
            LOG.warn("Failed to prevent sleep (caffeinate): {}", e.getMessage());
            process = null;
        }
    }

    /** Allow system to sleep again. */
    public synchronized void allowSleep() {
        if (!active) return;
        active = false;

        if (restartExecutor != null) {
            restartExecutor.shutdownNow();
            restartExecutor = null;
        }
        killProcess();
        LOG.info("Sleep prevention stopped");
    }

    private void killProcess() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
        process = null;
    }

    /** Check if sleep prevention is active. */
    public synchronized boolean isActive() {
        return active;
    }
}
