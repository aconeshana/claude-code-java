package com.claudecode.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionManagerAgentTranscriptTest {

    @Test
    void findAgentTranscriptPathResolvesCrossProjectSubagentTranscript(@TempDir Path tempDir)
            throws Exception {
        Path configDir = tempDir.resolve(".claude");
        Path mainProject = tempDir.resolve("main-project");
        Path otherProject = tempDir.resolve("other-project");
        Files.createDirectories(mainProject);
        Files.createDirectories(otherProject);

        String sessionId = "c3595190-819b-40b1-9754-c2b0330aa6fa";
        String agentId = "a8e5542325da5d8b9";

        SessionManager otherManager = new SessionManager(configDir, otherProject.toString());
        Path expected = otherManager.getAgentTranscriptPath(sessionId, agentId);
        Files.createDirectories(expected.getParent());
        Files.writeString(expected, "{\"type\":\"user\",\"agentId\":\"" + agentId + "\"}\n");

        SessionManager mainManager = new SessionManager(configDir, mainProject.toString());
        assertThat(mainManager.findAgentTranscriptPath(sessionId, agentId))
            .contains(expected);
        assertThat(mainManager.findAgentTranscriptPath(sessionId, "missing-agent"))
            .isEmpty();
    }
}
