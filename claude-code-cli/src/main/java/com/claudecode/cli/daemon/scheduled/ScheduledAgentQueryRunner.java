package com.claudecode.cli.daemon.scheduled;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Runs one scheduled prompt through a fresh headless Claude Code process. */
final class ScheduledAgentQueryRunner implements ScheduledTaskRunner {

    interface ProcessLauncher {
        Process launch(List<String> command, Path directory, Map<String, String> environment)
            throws Exception;
    }

    private final List<String> executableCommand;
    private final ProcessLauncher launcher;
    private final Consumer<String> log;

    ScheduledAgentQueryRunner(List<String> executableCommand, Consumer<String> log) {
        this(executableCommand, ScheduledAgentQueryRunner::launchProcess, log);
    }

    ScheduledAgentQueryRunner(List<String> executableCommand, ProcessLauncher launcher,
                              Consumer<String> log) {
        this.executableCommand = List.copyOf(executableCommand);
        this.launcher = launcher;
        this.log = log == null ? _ -> {} : log;
    }

    static List<String> commandFor(Path executable, ScheduledTaskConfig task) {
        return commandFor(List.of(executable.toString()), task);
    }

    static List<String> commandFor(List<String> executable, ScheduledTaskConfig task) {
        List<String> command = new ArrayList<>(executable);
        command.add("--print");
        command.add("--output-format");
        command.add("json");
        command.add("--permission-mode");
        command.add(task.permissionMode().wireValue());
        command.add("--setting-sources");
        command.add("user,project,local");
        if (task.model() != null) {
            command.add("--model");
            command.add(task.model());
        }
        command.add(task.prompt());
        return List.copyOf(command);
    }

    @Override
    public CompletableFuture<ScheduledTaskResult> run(ScheduledTaskConfig task) {
        CompletableFuture<ScheduledTaskResult> result = new CompletableFuture<>();
        AtomicReference<Process> processRef = new AtomicReference<>();
        result.whenComplete((_, _) -> {
            if (!result.isCancelled()) return;
            Process process = processRef.get();
            if (process != null && process.isAlive()) process.destroyForcibly();
        });
        Thread.startVirtualThread(() -> {
            try {
                Map<String, String> environment = Map.of(
                    "CLAUDE_CODE_ENTRYPOINT", "sdk",
                    "CLAUDE_CODE_WORKLOAD", "cron");
                Process process = launcher.launch(
                    commandFor(executableCommand, task), task.directory(), environment);
                processRef.set(process);
                Thread outputDrain = Thread.startVirtualThread(() -> drain(process.getInputStream()));
                Thread errorDrain = Thread.startVirtualThread(() -> drain(process.getErrorStream()));
                boolean exited = process.waitFor(task.runTimeoutMinutes(), TimeUnit.MINUTES);
                if (!exited) {
                    process.destroyForcibly();
                    process.waitFor();
                    result.complete(ScheduledTaskResult.failure(
                        "timed out after " + task.runTimeoutMinutes() + " minutes"));
                } else if (process.exitValue() == 0) {
                    result.complete(ScheduledTaskResult.success("exit=0"));
                } else {
                    result.complete(ScheduledTaskResult.failure("exit=" + process.exitValue()));
                }
                outputDrain.join();
                errorDrain.join();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                Process process = processRef.get();
                if (process != null && process.isAlive()) process.destroyForcibly();
                result.completeExceptionally(failure);
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void drain(InputStream source) {
        try (source) {
            source.transferTo(OutputStream.nullOutputStream());
        } catch (Exception failure) {
            log.accept("scheduled query output drain failed: " + failure.getMessage());
        }
    }

    private static Process launchProcess(
            List<String> command, Path directory, Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(directory.toFile());
        builder.environment().putAll(environment);
        return builder.start();
    }
}
