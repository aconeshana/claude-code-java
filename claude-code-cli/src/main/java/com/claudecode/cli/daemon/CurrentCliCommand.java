package com.claudecode.cli.daemon;

import com.claudecode.cli.ClaudeCodeCli;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Reconstructs a command that launches this CLI in a fresh JVM.
 *
 * <p>Two very different runtimes host this method, and they expose different
 * answers to every question it has to ask. Under a JVM the properties that
 * describe the current invocation are populated; in a GraalVM native image
 * {@code java.home} and {@code sun.java.command} are absent ({@code
 * getProperty} returns null) and {@code java.class.path} is the empty string,
 * because an image is not launched by a JVM. Building a {@code java -cp ...}
 * command there produced a command that could never start — and the null
 * {@code java.home} tripped a {@link NullPointerException} in {@link Path#of}
 * before it got that far, so {@code claude web -d} and every daemon worker
 * launch failed outright under the native build.
 */
public final class CurrentCliCommand {

    /**
     * Set by GraalVM at run time only inside a native image. Its presence is the
     * supported way to tell an image apart from a JVM; the JVM properties this
     * class otherwise relies on are unreliable in both directions.
     */
    private static final String NATIVE_IMAGE_MARKER = "org.graalvm.nativeimage.imagecode";

    private CurrentCliCommand() {}

    public static List<String> resolve() {
        if (System.getProperty(NATIVE_IMAGE_MARKER) != null) return nativeImageCommand();
        return jvmCommand();
    }

    /**
     * A native image is its own launcher, so the command is just the path to this
     * executable. {@code ProcessHandle} reports the path the process was started
     * with, which is absolute for every supported launcher — the native binary is
     * invoked directly by path, not through a PATH lookup.
     */
    private static List<String> nativeImageCommand() {
        return ProcessHandle.current().info().command()
            .filter(StringUtils::isNotBlank)
            .map(List::of)
            .orElseThrow(() -> new IllegalStateException(
                "cannot determine this executable's path; relaunching the CLI requires it"));
    }

    private static List<String> jvmCommand() {
        String java = javaExecutable();
        String sunCommand = StringUtils.trimToEmpty(System.getProperty("sun.java.command"));
        if (!sunCommand.isEmpty()) {
            String first = sunCommand.split("\\s+", 2)[0];
            if (Strings.CS.endsWith(first, ".jar")) return List.of(java, "-jar", first);
        }
        Optional<String> classpath = Optional.ofNullable(System.getProperty("java.class.path"))
            .filter(StringUtils::isNotBlank);
        if (classpath.isEmpty()) {
            throw new IllegalStateException(
                "cannot determine the classpath needed to relaunch the CLI");
        }
        return List.of(java, "-cp", classpath.get(), ClaudeCodeCli.class.getName());
    }

    /**
     * {@code java.home} points at the JDK that is running this process, which is
     * where its {@code java} launcher lives. A JVM always defines it, but the
     * check keeps a stripped-down or embedded runtime from producing a command
     * with a literal "null" path.
     */
    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        if (StringUtils.isBlank(home)) {
            throw new IllegalStateException("java.home is not set; cannot locate the java launcher");
        }
        return Path.of(home, "bin", "java").toString();
    }
}