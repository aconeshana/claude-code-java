package com.claudecode.services.claudemd;

import java.util.Locale;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.session.SessionManager;
import com.claudecode.core.git.GitUtils;
import org.apache.commons.lang3.StringUtils;
import java.nio.file.Path;

/**
 * Resolves the per-agent persistent memory directory.
 */
public final class AgentMemory {
    private AgentMemory() {}

    /** Sanitize an agent type for path use — colons → dashes. */
    private static String sanitize(String agentType) {
        return agentType.replace(':', '-');
    }

    /**
     * @param scope one of {@code "user"}, {@code "project"}, {@code "local"};
     *              case-insensitive; anything else returns {@code null}
     */
    public static Path getMemoryDir(String agentType, String scope, Path cwd, Path configHome) {
        if (agentType == null || scope == null) return null;
        String dirName = sanitize(agentType);
        String remoteEnv = SubprocessEnvironment.get(
            "CLAUDE_CODE_REMOTE_MEMORY_DIR");
        return switch (scope.toLowerCase(Locale.ROOT)) {
            case "user" -> {
                Path base = (StringUtils.isNotBlank(remoteEnv))
                    ? Path.of(remoteEnv) : configHome;
                yield base.resolve("agent-memory").resolve(dirName);
            }
            case "project" -> cwd.resolve(".claude").resolve("agent-memory").resolve(dirName);
            case "local" -> {
                if (StringUtils.isNotBlank(remoteEnv)) {
                    Path root = GitUtils.findCanonicalGitRoot(cwd);
                    String namespace = SessionManager.sanitizePath(
                        (root != null ? root : cwd.toAbsolutePath().normalize()).toString());
                    yield Path.of(remoteEnv)
                        .resolve("projects").resolve(namespace)
                        .resolve("agent-memory-local").resolve(dirName);
                }
                yield cwd.resolve(".claude").resolve("agent-memory-local").resolve(dirName);
            }
            default -> null;
        };
    }

}
