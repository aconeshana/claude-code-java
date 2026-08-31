package com.claudecode.http;

/** Isolated-JVM probe for the holder-based transport initialization boundary. */
public final class SharedHttpClientInitializationProbe {

    private SharedHttpClientInitializationProbe() { }

    public static void main(String[] args) {
        System.out.println(SharedHttpClient.isInitializedForTest());
        SharedHttpClient.shared();
        System.out.println(SharedHttpClient.isInitializedForTest());
    }
}
