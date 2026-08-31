package com.claudecode.tools.bash;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BashToolProcessTreeTest {

    @Test
    void timeoutDoesNotLeaveAChildProcessReadingTheTerminal(@TempDir Path project)
            throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac")
            || System.getProperty("os.name", "").toLowerCase().contains("linux"));
        BashTool tool = new BashTool();
        Path pidFile = project.resolve("child.pid");
        ToolExecutionContext context = ToolExecutionContext.builder(
                new AbortController(), "process-tree-timeout")
            .workingDirectory(project.toString())
            .build();

        String result = (String) tool.call(new ObjectMapper().createObjectNode()
            .put("command", "sleep 30 & echo $! > child.pid; wait")
            .put("timeout", 300), context);

        assertTrue(Strings.CI.contains(result, "timed out"), result);
        assertTrue(Files.exists(pidFile));
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        ProcessHandle child = ProcessHandle.of(childPid).orElse(null);
        if (child != null) child.onExit().get(2, TimeUnit.SECONDS);
        assertFalse(child != null && child.isAlive(),
            "the timeout must not orphan the child process");
    }
}
