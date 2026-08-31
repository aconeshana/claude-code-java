package com.claudecode.services.dream;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AutoDreamEngineImplTest {

    private final AutoDreamEngineImpl engine = new AutoDreamEngineImpl(null, null);
    private String savedUserHome;
    private Path tempHome;

    @BeforeEach
    void setUp() throws IOException {
        savedUserHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("autodream-test");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", savedUserHome);
    }

    private void writeSettings(String json) throws IOException {
        Path settings = tempHome.resolve(".claude").resolve("settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, json);
    }

    @Test
    void gateClosedWhenGrowthBookRolloutIsAbsent() {

        assertFalse(engine.isGateOpen(), "gate must stay closed without rollout cache");
    }

    @Test
    void gateClosedWhenAutoDreamDisabled() throws IOException {
        writeSettings("{\"autoDreamEnabled\": false}");
        assertFalse(engine.isGateOpen(), "gate closed when autoDreamEnabled=false");
    }

    @Test
    void gateClosedWhenAutoMemoryDisabled() throws IOException {
        writeSettings("{\"autoMemoryEnabled\": false}");
        assertFalse(engine.isGateOpen(), "gate closed when autoMemoryEnabled=false");
    }

    @Test
    void buildExtraContainsToolConstraintsAndSessions() {
        String extra = AutoDreamEngineImpl.buildExtra(List.of("sess-a", "sess-b"));
        assertTrue(Strings.CS.contains(extra, "Tool constraints for this run"), "tool-constraint note");
        assertTrue(Strings.CS.contains(extra, "Shell access is restricted to read-only"), "read-only shell note");
        assertTrue(Strings.CS.contains(extra, "- sess-a"), "first session listed");
        assertTrue(Strings.CS.contains(extra, "- sess-b"), "second session listed");
        assertTrue(Strings.CS.contains(extra, "2"), "session count mentioned");
    }
}
