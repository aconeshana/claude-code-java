package com.claudecode.commands.tooling;

import com.claudecode.core.engine.SandboxConfig;

import java.nio.file.Path;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Command-facing views over tools-owned runtime state.
 */
public record ToolingCommandPorts(
    Resources resources,
    Plans plans,
    Tasks tasks,
    SkillAttribution skillAttribution,
    Collaboration collaboration,
    Sandbox sandbox
) {
    public interface Resources {
        List<Path> markdownFiles(Path directory) throws IOException, InterruptedException;
    }
    public interface Plans {
        Path planFile(String sessionId);
        void copy(String sourceSessionId, String targetSessionId);
    }
    public interface Tasks {
        record Snapshot(String id, String type, String description, Status status, Instant startedAt) {}
        enum Status { RUNNING, PENDING, COMPLETED, FAILED, KILLED }
        List<Snapshot> list();
    }
    public interface SkillAttribution {
        void record(String commandName, String logicalPath, String content);
    }
    public interface Collaboration {
        boolean isTeammateSession();
    }
    public interface Sandbox {
        record Status(boolean dependenciesAvailable, boolean platformSupported) {}
        Status status(SandboxConfig config);
    }

    public static ToolingCommandPorts none() {
        Resources resources = _ -> List.of();
        Tasks tasks = List::of;
        SkillAttribution skillAttribution = (_, _, _) -> {
        };
        Collaboration collaboration = () -> false;
        Sandbox sandbox = _ -> new Sandbox.Status(false, false);
        Plans plans = new Plans() {
            @Override public Path planFile(String sessionId) { return Path.of(sessionId + ".md"); }
            @Override public void copy(String sourceSessionId, String targetSessionId) { }
        };
        return new ToolingCommandPorts(resources, plans, tasks,
            skillAttribution, collaboration, sandbox);
    }
}
