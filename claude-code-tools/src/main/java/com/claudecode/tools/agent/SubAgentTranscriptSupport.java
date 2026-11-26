package com.claudecode.tools.agent;

import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.session.AgentMetadata;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.session.TranscriptRecorder;
import com.claudecode.tools.worktree.WorktreeService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages sidechain transcript path resolution, {@link TranscriptRecorder} construction,
 * and {@code .meta.json} persistence for sub-agent invocations.
 */
final class SubAgentTranscriptSupport {

    private static final Logger log = LoggerFactory.getLogger(SubAgentTranscriptSupport.class);

    private final SessionIdentity sessionIdentity;
    private final Path claudeHomeOverride;
    private final String workingDirectory;

    SubAgentTranscriptSupport(
            SessionIdentity sessionIdentity, Path claudeHomeOverride, String workingDirectory) {
        this.sessionIdentity = sessionIdentity;
        this.claudeHomeOverride = claudeHomeOverride;
        this.workingDirectory = workingDirectory;
    }

    TranscriptRecorder buildSidechainTranscriptSink(String agentId,
                                                     String transcriptSubdir,
                                                     String attributionAgent,
                                                     String parentSessionId) {
        String effectiveSessionId = sidechainSessionId(parentSessionId);
        if (effectiveSessionId == null) {
            return null;
        }
        SessionManager sidechainSessionManager = sidechainSessionManager();
        Path explicit = sidechainTranscriptPath(agentId, transcriptSubdir, effectiveSessionId);
        return new TranscriptRecorder(sidechainSessionManager, new SessionStorage(),
            workingDirectory, /* isSidechain */ true, agentId, explicit,
            attributionAgent);
    }

    private String sidechainSessionId(String parentSessionId) {
        if (StringUtils.isNotBlank(parentSessionId)) {
            return parentSessionId;
        }
        return sessionIdentity != null ? sessionIdentity.get() : null;
    }

    private SessionManager sidechainSessionManager() {
        return claudeHomeOverride != null
            ? new SessionManager(claudeHomeOverride, workingDirectory)
            : new SessionManager(workingDirectory);
    }

    Path sidechainTranscriptPath(String agentId, String transcriptSubdir,
                                         String parentSessionId) {
        String effectiveSessionId = sidechainSessionId(parentSessionId);
        if (effectiveSessionId == null) return null;
        SessionManager manager = sidechainSessionManager();
        if (StringUtils.isBlank(transcriptSubdir)) {
            return manager.getAgentTranscriptPath(effectiveSessionId, agentId).normalize();
        }
        Path base = manager.getProjectDir()
            .resolve(effectiveSessionId).resolve("subagents").normalize();
        Path explicit = base.resolve(transcriptSubdir)
            .resolve("agent-" + agentId + ".jsonl").normalize();
        if (!explicit.startsWith(base)) {
            throw new IllegalArgumentException("Invalid sub-agent transcript subdirectory");
        }
        return explicit;
    }

    void writeWorkflowAgentMetadata(String agentId, SubAgentRequest request) {
        String transcriptSubdir = request.transcriptSubdir();
        if (sessionIdentity == null || transcriptSubdir == null) return;
        SessionManager manager = claudeHomeOverride != null
            ? new SessionManager(claudeHomeOverride, workingDirectory)
            : new SessionManager(workingDirectory);
        Path base = manager.getProjectDir().resolve(sessionIdentity.get())
            .resolve("subagents").normalize();
        Path metadata = base.resolve(transcriptSubdir)
            .resolve("agent-" + agentId + ".meta.json").normalize();
        if (!metadata.startsWith(base)) {
            throw new IllegalArgumentException("Invalid sub-agent transcript subdirectory");
        }
        try {
            Files.createDirectories(metadata.getParent());
            ObjectNode json = JsonUtils.getMapper().createObjectNode();
            json.put("agentType", "workflow-subagent");
            json.put("spawnDepth", Math.max(0, request.agentDepth()));
            json.put("subagentMaxDepth", SubAgentToolRestrictions.normalizedMaxDepth(
                request.subagentMaxDepthSnapshot()));
            Files.writeString(metadata, JsonUtils.getMapper().writeValueAsString(json),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to persist workflow-agent metadata {}: {}",
                metadata, e.getMessage());
        }
    }

    void writeAgentMetadata(String agentId, SubAgentRequest request,
                                    WorktreeService.AgentWorktree agentWorktree) {
        if (request == null) return;
        String parentSessionId = request.parentContext() != null
            ? request.parentContext().sessionId() : null;
        Path transcript = sidechainTranscriptPath(agentId, null, parentSessionId);
        if (transcript == null) return;
        SessionStorage storage = new SessionStorage();
        String worktreePath = agentWorktree == null ? null : agentWorktree.worktreePath();
        if (worktreePath == null && StringUtils.isNotBlank(request.cwd())) {
            try {
                AgentMetadata previous = storage.readAgentMetadata(transcript).orElse(null);
                if (previous != null && Strings.CS.equals(previous.worktreePath(), request.cwd())
                        && Files.isDirectory(Path.of(previous.worktreePath()))) {
                    worktreePath = previous.worktreePath();
                }
            } catch (RuntimeException error) {
                log.debug("Unable to preserve prior agent worktree metadata for {}", agentId, error);
            }
        }
        String agentType = request.fork() ? "fork"
            : StringUtils.defaultIfBlank(request.subagentType(), "general-purpose");
        String description = StringUtils.isBlank(request.description())
            ? null : request.description();
        try {
            storage.writeAgentMetadata(transcript,
                new AgentMetadata(agentType, worktreePath, description, false,
                    Math.max(0, request.agentDepth()), request.teammate() ? null
                        : SubAgentToolRestrictions.normalizedMaxDepth(request.subagentMaxDepthSnapshot())));
        } catch (RuntimeException error) {
            log.warn("Failed to persist agent metadata {}: {}", agentId, error.getMessage());
        }
    }
}
