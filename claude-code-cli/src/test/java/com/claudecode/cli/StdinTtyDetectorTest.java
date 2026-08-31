package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class StdinTtyDetectorTest {

    @Test
    void pipedStdinIsDetectedIndependentlyOfTheControllingConsole() throws Exception {
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            Probe.class.getName())
            .redirectErrorStream(true)
            .start();

        process.getOutputStream().write("PIPE".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();

        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("nested JVM did not exit");
        }

        assertEquals(0, process.exitValue());
        assertEquals("false\n", new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void devNullIsNotMistakenForATty() throws Exception {
        Assumptions.assumeTrue(File.separatorChar == '/', "POSIX /dev/null test");
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            Probe.class.getName())
            .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
            .redirectErrorStream(true)
            .start();

        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("nested JVM did not exit");
        }

        assertEquals(0, process.exitValue());
        assertEquals("false\n", new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    public static final class Probe {
        public static void main(String[] args) {
            System.out.println(StdinTtyDetector.isStdinTty());
        }
    }
}
