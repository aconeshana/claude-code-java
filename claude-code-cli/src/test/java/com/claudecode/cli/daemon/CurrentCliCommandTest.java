package com.claudecode.cli.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/**
 * Guards the two runtimes that host this method. The JVM cases run everywhere;
 * the native cases drive the native branch directly, because a test JVM cannot
 * itself be a native image. Both matter: the JVM branch is what the ordinary
 * build exercises, while the native branch is what the shipped binary uses, and
 * a regression in the latter is invisible to every other test in this module.
 */
class CurrentCliCommandTest {

    /** The marker GraalVM sets at run time inside a native image. */
    private static final String NATIVE_IMAGE_MARKER = "org.graalvm.nativeimage.imagecode";

    @Test
    void jvmCommandNamesTheRunningJavaExecutable() {
        List<String> command = CurrentCliCommand.resolve();

        assertThat(command).isNotEmpty();
        String java = command.getFirst();
        assertThat(java).isNotEmpty();
        assertThat(Files.isExecutable(Path.of(java)))
            .as("the resolved java launcher must be runnable: %s", java)
            .isTrue();
        assertThat(java).startsWith(System.getProperty("java.home"));
    }

    @Test
    void jvmCommandFallsBackToTheClasspathWhenNoJarIsRunning() {
        List<String> command = CurrentCliCommand.resolve();

        assertThat(command).hasSize(4);
        assertThat(command.get(1)).isEqualTo("-cp");
        assertThat(command.get(2)).isEqualTo(System.getProperty("java.class.path"));
        assertThat(command.get(3)).isEqualTo("com.claudecode.cli.ClaudeCodeCli");
    }

    @Test
    void jvmCommandNeverLeavesANullOrEmptyClasspath() {
        // The native build reported these as null / "" and turned them into a
        // command that could not start. Anything the command carries has to be
        // a real value.
        assertThat(CurrentCliCommand.resolve()).allSatisfy(argument ->
            assertThat(argument)
                .as("no argument may be null or blank")
                .isNotNull()
                .isNotBlank());
    }

    @Test
    void nativeImageCommandIsTheExecutableItself() {
        String previous = System.getProperty(NATIVE_IMAGE_MARKER);
        System.setProperty(NATIVE_IMAGE_MARKER, "runtime");
        try {
            List<String> command = CurrentCliCommand.resolve();

            assertThat(command).hasSize(1);
            // This JVM's own executable, which is what an image would report for
            // itself; the point is that it is not a java -cp/-jar invocation.
            assertThat(command.getFirst())
                .isEqualTo(runningExecutable())
                .doesNotContain("-cp", "-jar");
        } finally {
            restore(NATIVE_IMAGE_MARKER, previous);
        }
    }

    @Test
    void nativeImageCommandNeverFallsBackToAJavaInvocation() {
        String previous = System.getProperty(NATIVE_IMAGE_MARKER);
        System.setProperty(NATIVE_IMAGE_MARKER, "runtime");
        try {
            // A java -cp/-jar command built from native-image properties is the
            // exact regression this guards: it looks plausible and cannot start.
            assertThat(CurrentCliCommand.resolve())
                .noneMatch(argument -> Strings.CS.equals("-cp", argument)
                    || Strings.CS.equals("-jar", argument));
        } finally {
            restore(NATIVE_IMAGE_MARKER, previous);
        }
    }

    @Test
    void failsLoudlyRatherThanReturningACommandWithANullClasspath() {
        String previousClasspath = System.getProperty("java.class.path");
        String previousCommand = System.getProperty("sun.java.command");
        System.clearProperty("java.class.path");
        System.setProperty("sun.java.command", "");
        try {
            assertThatThrownBy(CurrentCliCommand::resolve)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classpath");
        } finally {
            restore("java.class.path", previousClasspath);
            restore("sun.java.command", previousCommand);
        }
    }

    private static String runningExecutable() {
        return ProcessHandle.current().info().command().orElseThrow();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}