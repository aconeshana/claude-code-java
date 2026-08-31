package com.claudecode.core.process;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ProcessTreeTerminatorTest {

    @Test
    void terminatesDescendantsBeforeTheyCanBecomeOrphans() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac")
            || System.getProperty("os.name", "").toLowerCase().contains("linux"));
        Process root = new ProcessBuilder("/bin/sh", "-c",
            "sleep 30 & child=$!; printf '%s\\n' \"$child\"; wait").start();
        ProcessHandle child = null;
        try {
            String line = new BufferedReader(new InputStreamReader(
                root.getInputStream(), StandardCharsets.UTF_8)).readLine();
            child = ProcessHandle.of(Long.parseLong(line)).orElseThrow();
            assertTrue(root.isAlive());
            assertTrue(child.isAlive());

            ProcessTreeTerminator.terminate(root, Duration.ofMillis(250));

            assertFalse(root.isAlive());
            assertFalse(child.isAlive(), "the child must not survive as an orphan");
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            if (root.isAlive()) root.destroyForcibly();
        }
    }
}
