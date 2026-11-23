package com.claudecode.cli;

import com.claudecode.gateway.GatewayHeadlessSessions;
import com.claudecode.gateway.GatewaySchedulePort;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.tools.cron.CronScheduler;
import com.claudecode.tools.cron.CronStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Resident scheduled-task daemon hosted inside the {@code claude web} process.
 *
 * <p>Uses the same {@link CronScheduler} and {@code CronSchedulerLock} lease as
 * the TUI so durable tasks survive TUI exit without duplicating fires when a
 * TUI is also open. Supports per-task working directory, new-vs-reused session
 * mode, model &amp; reasoning effort overrides, native macOS notifications, and
 * auto-closing completed sessions when archive-on-success is enabled.
 */
final class CliWebCronDaemon implements AutoCloseable {

    static final String SCHEDULED_PREFIX = "[定时任务] ";

    static String formatScheduledTag(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            return SCHEDULED_PREFIX;
        }
        return "[定时任务#" + taskId.trim() + "] ";
    }

    private static final Logger log = Logger.getLogger(CliWebCronDaemon.class.getName());
    private static final boolean IS_MAC = Strings.CI.contains(System.getProperty("os.name", ""), "mac");

    private final CliHeadlessGatewaySessions headless;
    private final String workingDirectory;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentHashMap<String, String> reusedSessionByTaskId = new ConcurrentHashMap<>();
    private final CronScheduler scheduler;

    CliWebCronDaemon(CliHeadlessGatewaySessions headless, String workingDirectory) {
        this.headless = Objects.requireNonNull(headless, "headless");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.scheduler = new CronScheduler(
            () -> false,
            this::fireAsync,
            Path.of(workingDirectory),
            "web-daemon",
            closed::get);
    }

    /** Starts the resident scheduler. */
    void start() {
        scheduler.start();
    }

    /** Gateway port wired into {@code /api/schedule} for the web daemon. */
    GatewaySchedulePort port() {
        return new GatewaySchedulePort() {
            @Override public List<ScheduleEntry> list() {
                return CronStore.list().stream()
                    .map(CliWebCronDaemon::toEntry)
                    .toList();
            }

            @Override public String validateAdd(String cron) {
                return CronStore.validateNewJob(cron);
            }

            @Override public String validateUpdate(String cron) {
                return CronStore.validateCronExpression(cron);
            }

            @Override public String add(String cron, String prompt,
                    boolean recurring, boolean durable, String model) {
                return CronStore.addWithModel(cron, prompt, recurring, durable, model);
            }

            @Override public String add(String cron, String prompt,
                    boolean recurring, boolean durable, TaskOptions options) {
                return CronStore.addWithOptions(cron, prompt, recurring, durable, toCronOptions(options));
            }

            @Override public boolean update(String id, String cron, String prompt,
                    boolean recurring, boolean durable, TaskOptions options) {
                return CronStore.update(id, cron, prompt, recurring, durable, toCronOptions(options));
            }

            @Override public boolean setEnabled(String id, boolean enabled) {
                return CronStore.setEnabled(id, enabled);
            }

            @Override public boolean remove(String id) {
                reusedSessionByTaskId.remove(id);
                return CronStore.removeById(id);
            }

            @Override public boolean runNow(String id) {
                CronStore.CronJob job = CronStore.findById(id);
                if (job == null) return false;
                fireAsync(new CronScheduler.FiredTask(
                    job.id(), job.prompt(), job.prompt(), job.kind(),
                    job.recurring(), job.agentId(), job.model()));
                return true;
            }
        };
    }

    static GatewaySchedulePort.ScheduleEntry toEntry(CronStore.CronJob job) {
        CronStore.CronOptions opts = job.resolvedOptions();
        return new GatewaySchedulePort.ScheduleEntry(
            job.id(), job.cron(), job.prompt(), job.recurring(),
            job.durable(), job.createdAt(), job.lastFiredAt(),
            job.kind(), job.agentId(), job.createdBySessionId(),
            opts.model(), opts.effort(), opts.workingDirectory(),
            opts.newSessionPerRun(), opts.notificationMode(), opts.archiveOnSuccess(),
            opts.enabled());
    }

    static CronStore.CronOptions toCronOptions(GatewaySchedulePort.TaskOptions options) {
        if (options == null) return CronStore.CronOptions.DEFAULT;
        return CronStore.CronOptions.normalize(new CronStore.CronOptions(
            options.model(),
            options.effort(),
            options.workingDirectory(),
            options.newSessionPerRun(),
            options.notificationMode(),
            options.archiveOnSuccess(),
            options.enabled()));
    }

    /** Executes one scheduled task in a headless session with bypassPermissions. */
    void fireAsync(CronScheduler.FiredTask task) {
        if (task == null || StringUtils.isBlank(task.prompt())) return;
        Thread.ofVirtual().name("web-cron-" + task.id()).start(() -> runTask(task));
    }

    private void runTask(CronScheduler.FiredTask task) {
        CronStore.CronJob job = CronStore.findById(task.id());
        CronStore.CronOptions opts = job != null
            ? job.resolvedOptions()
            : CronStore.CronOptions.normalize(new CronStore.CronOptions(
                task.model(), null, null, true, "off", false));
        String targetCwd = StringUtils.defaultIfBlank(opts.workingDirectory(), workingDirectory);
        String reuseId = opts.newSessionPerRun() ? null : reusedSessionByTaskId.get(task.id());
        String openedSessionId = null;
        boolean succeeded = false;
        try {
            var opened = headless.open(new GatewayHeadlessSessions.OpenRequest(reuseId, targetCwd));
            SessionHostSession session = opened.session();
            openedSessionId = session.info().id();
            if (!opts.newSessionPerRun() && StringUtils.isNotBlank(openedSessionId)) {
                reusedSessionByTaskId.put(task.id(), openedSessionId);
            }
            try {
                session.permissions().set("bypassPermissions");
            } catch (RuntimeException failure) {
                log.log(Level.WARNING, "Failed to set bypassPermissions for cron task " + task.id(),
                    failure);
            }
            String modelOverride = StringUtils.defaultIfBlank(opts.model(), task.model());
            if (StringUtils.isNotBlank(modelOverride)) {
                try {
                    session.models().set(modelOverride.trim());
                } catch (RuntimeException failure) {
                    log.log(Level.WARNING, "Failed to apply model override " + modelOverride
                        + " for cron task " + task.id(), failure);
                }
            }
            if (StringUtils.isNotBlank(opts.effort())) {
                try {
                    session.efforts().set(opts.effort().trim());
                } catch (RuntimeException failure) {
                    log.log(Level.WARNING, "Failed to apply reasoning effort " + opts.effort()
                        + " for cron task " + task.id(), failure);
                }
            }
            String body = StringUtils.defaultIfBlank(task.resolvedPrompt(), task.prompt()).trim();
            String tag = formatScheduledTag(task.id());
            String taggedPrompt = Strings.CS.startsWith(body, "[定时任务") ? body : tag + body;
            SessionHostSubmission submission = new SessionHostSubmission(
                taggedPrompt,
                "cron-" + task.id() + "-" + UUID.randomUUID().toString().substring(0, 8),
                List.of(),
                List.of());
            session.submit(submission).toCompletableFuture().join();
            succeeded = true;
            if (opts.archiveOnSuccess() && opts.newSessionPerRun() && StringUtils.isNotBlank(openedSessionId)) {
                headless.close(openedSessionId);
            }
        } catch (RuntimeException failure) {
            log.log(Level.WARNING, "Scheduled task " + task.id() + " execution failed", failure);
        } finally {
            maybeNotify(task, opts.notificationMode(), succeeded);
        }
    }

    private static void maybeNotify(CronScheduler.FiredTask task, String mode, boolean succeeded) {
        if (!IS_MAC) return;
        boolean shouldSend = Strings.CS.equals("always", mode)
            || (Strings.CS.equals("on_error", mode) && !succeeded);
        if (!shouldSend) return;
        String headline = task.prompt().trim().split("\\R", 2)[0];
        if (headline.length() > 42) {
            headline = headline.substring(0, 42) + "…";
        }
        String status = succeeded ? "已完成" : "执行失败";
        String body = sanitizeAppleScript(status + "：" + headline);
        String script = "display notification \"" + body + "\" with title \"Poco 定时任务\"";
        try {
            new ProcessBuilder("osascript", "-e", script).start();
        } catch (IOException failure) {
            log.log(Level.FINE, "Failed to send macOS notification for cron task " + task.id(), failure);
        }
    }

    private static String sanitizeAppleScript(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.stop();
        }
    }
}
