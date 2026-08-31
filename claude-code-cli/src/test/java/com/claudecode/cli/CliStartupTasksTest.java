package com.claudecode.cli;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliStartupTasksTest {

    @Test
    void runReturnsBeforeTaskCompletesAndFutureTracksCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        var completion = CliStartupTasks.run("test-startup-task", () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertFalse(completion.isDone());
        release.countDown();
        completion.join();
        assertTrue(completion.isDone());
    }

    @Test
    void taskFailureLogRetainsTaskNameAndCauseWithoutExceptionMessages() {
        Logger logger = (Logger) LoggerFactory.getLogger(CliStartupTasks.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var completion = CliStartupTasks.run("diagnostic-startup-task", () -> {
                throw new IllegalStateException(
                    "DO_NOT_LOG_STARTUP_TASK_OUTPUT",
                    new IOException("DO_NOT_LOG_STARTUP_TASK_CAUSE"));
            });

            assertThrows(CompletionException.class, completion::join);
            ILoggingEvent event = appender.list.stream()
                .filter(item -> Strings.CS.contains(
                    item.getFormattedMessage(), "Startup task failed"))
                .findFirst()
                .orElseThrow();
            assertTrue(Strings.CS.contains(
                event.getFormattedMessage(), "diagnostic-startup-task"));
            assertTrue(Strings.CS.contains(event.getFormattedMessage(),
                "failureType=" + IllegalStateException.class.getName()));
            assertNotNull(event.getThrowableProxy());
            assertEquals(IllegalStateException.class.getName(),
                event.getThrowableProxy().getMessage());
            assertNotNull(event.getThrowableProxy().getCause());
            assertEquals(IOException.class.getName(),
                event.getThrowableProxy().getCause().getMessage());
            assertFalse(Strings.CS.contains(event.getThrowableProxy().getMessage(),
                "DO_NOT_LOG_STARTUP_TASK_OUTPUT"));
            assertFalse(Strings.CS.contains(event.getThrowableProxy().getCause().getMessage(),
                "DO_NOT_LOG_STARTUP_TASK_CAUSE"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
