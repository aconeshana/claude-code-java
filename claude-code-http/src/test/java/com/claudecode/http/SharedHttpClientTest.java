package com.claudecode.http;

import org.apache.commons.lang3.Strings;
import okhttp3.OkHttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(OrderAnnotation.class)
class SharedHttpClientTest {

    @Test
    @Order(1)
    void classLoadingDoesNotInitializeTlsTransport() throws Exception {
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            SharedHttpClientInitializationProbe.class.getName())
            .redirectErrorStream(true)
            .start();
        String output = new String(
            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        assertEquals(0, process.waitFor(), output);
        assertTrue(Strings.CS.startsWith(output, "false\ntrue"), output);
    }

    @Test
    @Order(2)
    void returnsOneApplicationWideBaseClient() {
        assertSame(SharedHttpClient.shared(), SharedHttpClient.shared());
    }

    @Test
    @Order(3)
    void enablesFastFallback() {
        assertTrue(SharedHttpClient.shared().fastFallback());
    }

    @Test
    @Order(4)
    void derivedClientsShareTransportResources() {
        OkHttpClient base = SharedHttpClient.shared();
        OkHttpClient derived = base.newBuilder().build();

        assertSame(base.connectionPool(), derived.connectionPool());
        assertSame(base.dispatcher(), derived.dispatcher());
    }

    @Test
    @Order(5)
    void baseClientDoesNotCarryDomainRetryPolicy() {
        assertTrue(SharedHttpClient.shared().interceptors().isEmpty());
    }
}
