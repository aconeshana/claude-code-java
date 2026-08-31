package com.claudecode.app.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One executable form of the application, and how to invoke it.
 */
record SmokeTarget(String name, List<String> launch) {

    /** Native flavours in the order a developer most likely rebuilt them. */
    private static final List<String> NATIVE_FLAVOURS =
        List.of("nativeQuickCompile", "nativeCompile", "nativeReleaseCompile");

    /**
     * The jar is supplied by the task that runs this, which builds it first. Native images are
     * only discovered, because they sit off the default build path and cannot be built without a
     * GraalVM toolchain — a missing one degrades to a reported skip.
     *
     * <p>{@code smoke.targets} narrows the result to a comma-separated list of names. Iterating on
     * the plan against the jar alone is otherwise needlessly slow, since every case is a real
     * process and a stale native binary fails for reasons the plan is not being edited about.
     *
     * @param jar            the fat jar, or {@code null} when the harness was run without one
     * @param buildDirectory this module's build directory, the only place native images appear
     */
    static List<SmokeTarget> discover(Path jar, Path buildDirectory) {
        List<SmokeTarget> targets = new ArrayList<>();
        if (jar != null && Files.isRegularFile(jar)) {
            targets.add(new SmokeTarget("jar", List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString())));
        }
        for (String flavour : NATIVE_FLAVOURS) {
            Path binary = buildDirectory.resolve("native/" + flavour + "/claude-code-app");
            if (Files.isExecutable(binary)) {
                targets.add(new SmokeTarget(flavour, List.of(binary.toString())));
            }
        }
        List<String> requested = requestedNames();
        return requested.isEmpty()
            ? List.copyOf(targets)
            : targets.stream().filter(target -> requested.contains(target.name())).toList();
    }

    /** The names {@code smoke.targets} asked for, empty when it asked for everything. */
    static List<String> requestedNames() {
        String requested = System.getProperty("smoke.targets", "").strip();
        if (requested.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(requested.split(",")).map(String::strip)
            .filter(name -> !name.isEmpty()).toList();
    }

    /**
     * Names the harness looked for but did not find, so a skip can say what is missing. A flavour
     * {@code smoke.targets} excluded is not missing — it was never asked for — so it is left out
     * here and reported as the filter instead.
     */
    static List<String> missingNativeFlavours(List<SmokeTarget> discovered) {
        List<String> present = discovered.stream().map(SmokeTarget::name).toList();
        List<String> requested = requestedNames();
        return NATIVE_FLAVOURS.stream()
            .filter(flavour -> !present.contains(flavour))
            .filter(flavour -> requested.isEmpty() || requested.contains(flavour))
            .toList();
    }

    List<String> commandFor(List<String> arguments) {
        List<String> command = new ArrayList<>(launch);
        command.addAll(arguments);
        return List.copyOf(command);
    }
}
