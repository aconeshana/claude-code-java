package com.claudecode.cli;

import com.claudecode.commands.tooling.ToolingCommandPorts;
import com.claudecode.tools.files.RipGrepUtil;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.sandbox.PlatformSandboxManager;
import com.claudecode.tools.sandbox.SandboxManager;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;

import java.nio.file.Path;

/**
 * CLI leaf mappings for tools-owned command capabilities.
 */
final class CliToolingCommandAdapter {
    private CliToolingCommandAdapter() { }

    static ToolingCommandPorts create(TaskRegistry tasks, InvokedSkillRegistry invokedSkills) {
        SandboxManager sandbox = PlatformSandboxManager.create();
        return new ToolingCommandPorts(
            RipGrepUtil::listMarkdownFiles,
            new ToolingCommandPorts.Plans() {
                @Override public Path planFile(String sessionId) {
                    return PlanFiles.getPlanFilePath(sessionId, null);
                }
                @Override public void copy(String sourceSessionId, String targetSessionId) {
                    PlanFiles.copyPlanForFork(sourceSessionId, targetSessionId);
                }
            },
            () -> tasks.store().list().stream().map(task ->
                new ToolingCommandPorts.Tasks.Snapshot(task.id(), task.type().name(),
                    task.description(), ToolingCommandPorts.Tasks.Status.valueOf(task.status().name()),
                    task.startTime())).toList(),
            (commandName, logicalPath, content) -> invokedSkills
                .record(null, commandName, logicalPath, content),
            () -> TeammateContextHolder.get() != null,
            config -> new ToolingCommandPorts.Sandbox.Status(
                sandbox.available(), sandbox.isPlatformSupported(config)));
    }
}
