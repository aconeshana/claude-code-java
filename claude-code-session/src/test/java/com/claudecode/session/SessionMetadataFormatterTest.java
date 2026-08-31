package com.claudecode.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SessionMetadataFormatterTest {

    @Test
    void formatsTheSharedSessionListMetadataParts() {
        SessionInfo session = new SessionInfo("id", System.currentTimeMillis() - 2 * 86_400_000L,
            Instant.now().minusSeconds(2 * 86_400), 4, "summary", "main", "/project", "release");

        assertEquals("2 days ago · main · 1KB · #release · /project",
            SessionMetadataFormatter.format(session, 1024, true));
    }
}
