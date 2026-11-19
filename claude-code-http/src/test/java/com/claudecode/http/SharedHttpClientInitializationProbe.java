package com.claudecode.http;

/**
 * Isolated-JVM probe for the holder-based transport initialization boundary.
 *
 * <p>Suppresses {@code NoDirectProcessStreamAccess}: the probe runs in a JVM of its own and
 * prints the two holder states, which the spawning test reads back as the observation under
 * test. Routing them through {@code CliOutput} would mean wiring a writer into a process whose
 * only job is to put two lines on stdout.
 */
@SuppressWarnings("PMD.NoDirectProcessStreamAccess")
public final class SharedHttpClientInitializationProbe {

    private SharedHttpClientInitializationProbe() { }

    public static void main(String[] args) {
        System.out.println(SharedHttpClient.isInitializedForTest());
        SharedHttpClient.shared();
        System.out.println(SharedHttpClient.isInitializedForTest());
    }
}
