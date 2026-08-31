package com.claudecode.session;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStorageDiagnosticsTest {

    @TempDir
    Path tempDir;

    @Test
    void resumeMetadataReadFailureRetainsPathAndException() {
        Logger logger = (Logger) LoggerFactory.getLogger(SessionStorage.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertTrue(new SessionStorage().readSessionSlug(tempDir).isEmpty());

            ILoggingEvent event = appender.list.stream()
                .filter(item -> Strings.CS.contains(
                    item.getFormattedMessage(), "Failed to read session slug"))
                .findFirst()
                .orElseThrow();
            assertTrue(Strings.CS.contains(event.getFormattedMessage(), tempDir.toString()));
            assertNotNull(event.getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
