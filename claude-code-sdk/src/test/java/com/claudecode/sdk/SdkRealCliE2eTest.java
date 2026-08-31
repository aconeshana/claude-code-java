package com.claudecode.sdk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class SdkRealCliE2eTest {
    @TempDir Path temp;

    @Test
    @Timeout(30)
    void streamQueryCompletesInitializeHandshakeWithTheRealJavaCli() {
        QueryOptions options = QueryOptions.builder()
            .cwd(temp)
            .env(Map.of("CLAUDE_CONFIG_DIR", temp.resolve("config").toString()))
            .loadTimeout(Duration.ofSeconds(10)).build();

        try (SdkQuery query = ClaudeAgentSdk.query(List.of(), options)) {
            assertTrue(query.initializationResult().join().commands() != null);
            while (query.hasNext()) query.next();
        }
    }
}
