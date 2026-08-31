package com.claudecode.tools.bash;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration contract for phase-one sudo prompting in the model-facing Bash tool. */
class BashToolSudoPasswordTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void cancellationStopsBeforeStartingThePrivilegedCommand(@TempDir Path project) {
        BashTool tool = new BashTool();
        AtomicInteger requests = new AtomicInteger();
        tool.setSudoPasswordInteraction(request -> {
            requests.incrementAndGet();
            assertTrue(Path.of(request.executable()).isAbsolute(),
                "the credential prompt must identify the trusted absolute sudo executable");
            assertEquals("sudo", Path.of(request.executable()).getFileName().toString());
            assertEquals("sudo sh -c 'touch should-not-exist'", request.command());
            return SudoPasswordInteraction.Result.cancelled();
        });

        String result = (String) tool.call(mapper.createObjectNode()
            .put("command", "sudo sh -c 'touch should-not-exist'"),
            context(project, new ArrayList<>()));

        assertEquals(1, requests.get());
        assertTrue(Strings.CI.contains(result, "cancel"), result);
        assertFalse(Files.exists(project.resolve("should-not-exist")));
    }

    @Test
    void headlessModeFailsClosedInsteadOfStartingOrWaitingForSudo(@TempDir Path project) {
        BashTool tool = new BashTool(); // No UI credential provider is wired.
        long started = System.nanoTime();

        String result = (String) tool.call(mapper.createObjectNode()
            .put("command", "sudo sh -c 'touch should-not-exist'"),
            context(project, new ArrayList<>()));

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(Strings.CI.contains(result, "password input is unavailable"), result);
        assertTrue(elapsedMillis < 1_000, "headless sudo must fail before process startup");
        assertFalse(Files.exists(project.resolve("should-not-exist")));
    }

    @Test
    void compoundPasswordSudoFailsClosedBeforeProcessStartup(@TempDir Path project) {
        BashTool tool = new BashTool();
        AtomicInteger requests = new AtomicInteger();
        tool.setSudoPasswordInteraction(_ -> {
            requests.incrementAndGet();
            return SudoPasswordInteraction.Result.cancelled();
        });
        long started = System.nanoTime();

        String result = (String) tool.call(mapper.createObjectNode()
            .put("command", "sudo -k && sudo -v && sudo -n true && echo should-not-run"),
            context(project, new ArrayList<>()));

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(Strings.CI.contains(result, "direct sudo command"), result);
        assertEquals(0, requests.get());
        assertTrue(elapsedMillis < 1_000, "compound sudo must fail before process startup");
    }

    private static ToolExecutionContext context(
            Path workingDirectory, List<ToolExecutionContext.ProgressUpdate> progress) {
        return ToolExecutionContext.builder(new AbortController(), "sudo-red-test")
            .workingDirectory(workingDirectory.toString())
            .progressSink(progress::add)
            .build();
    }
}
