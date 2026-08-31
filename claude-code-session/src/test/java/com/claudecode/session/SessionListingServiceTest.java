package com.claudecode.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionListingServiceTest {

    @Test
    void defaultsListAllProjectsAndKeepProgrammaticSessions(@TempDir Path home) throws Exception {
        SessionManager cli = new SessionManager(home, "/repo/cli");
        SessionManager sdk = new SessionManager(home, "/repo/sdk");
        write(cli, "cli", "{\"type\":\"user\",\"message\":{\"content\":\"normal\"}}\n", 2_000);
        write(sdk, "sdk", "{\"type\":\"user\",\"entrypoint\":\"sdk-cli\",\"message\":{\"content\":\"programmatic\"}}\n", 1_000);

        List<SessionInfo> sessions = new SessionListingService(home)
            .listSessions(ListSessionsOptions.defaults());

        assertEquals(List.of("normal", "programmatic"), sessions.stream()
            .map(SessionInfo::firstPrompt).toList());
    }

    @Test
    void paginationAppliesAfterVisibilityAndDuplicateFallback(@TempDir Path home) throws Exception {
        SessionManager older = new SessionManager(home, "/repo/main");
        SessionManager newer = new SessionManager(home, "/repo/worktree");
        String duplicate = UUID.randomUUID().toString();
        write(older, duplicate, "{\"type\":\"user\",\"message\":{\"content\":\"older valid\"}}\n", 1_000);
        write(newer, duplicate, "{\"type\":\"user\",\"isSidechain\":true,\"message\":{\"content\":\"new hidden\"}}\n", 4_000);
        write(older, "metadata", "{\"type\":\"agent-name\",\"agentName\":\"only metadata\"}\n", 3_000);
        write(older, "visible", "{\"type\":\"user\",\"message\":{\"content\":\"visible\"}}\n", 2_000);

        List<SessionInfo> sessions = new SessionListingService(home).listSessions(
            new ListSessionsOptions(null, 1, 1, true, true));

        assertEquals(1, sessions.size());
        assertEquals("older valid", sessions.getFirst().firstPrompt());
    }

    @Test
    void sdkCustomTitleFallsBackToAiTitleAndProgrammaticCanBeExcluded(@TempDir Path home)
            throws Exception {
        SessionManager manager = new SessionManager(home, "/repo/main");
        String id = write(manager, "ai", "{\"type\":\"user\",\"entrypoint\":\"sdk-cli\",\"message\":{\"content\":\"prompt\"}}\n", 1_000);
        var title = JsonUtils.getMapper().createObjectNode();
        title.put("type", "ai-title");
        title.put("aiTitle", "Generated title");
        Files.writeString(manager.getSessionFile(id), title + "\n", StandardOpenOption.APPEND);

        SessionListingService service = new SessionListingService(home);
        assertEquals("Generated title", service.listSessions(new ListSessionsOptions(
            "/repo/main", 0, 0, true, true)).getFirst().customTitle());
        assertTrue(service.listSessions(new ListSessionsOptions(
            "/repo/main", 0, 0, true, false)).isEmpty());
    }

    @Test
    void includeWorktreesCanBeDisabled(@TempDir Path home) throws Exception {
        String main = "/repo/main";
        String worktree = "/repo/worktree";
        write(new SessionManager(home, main), "main", "{\"type\":\"user\",\"message\":{\"content\":\"main\"}}\n", 2_000);
        write(new SessionManager(home, worktree), "worktree", "{\"type\":\"user\",\"message\":{\"content\":\"worktree\"}}\n", 1_000);
        SessionListingService service = new SessionListingService(home, _ -> false,
            _ -> List.of(main, worktree));

        assertEquals(2, service.listSessions(new ListSessionsOptions(main, 0, 0, true, true)).size());
        assertEquals(1, service.listSessions(new ListSessionsOptions(main, 0, 0, false, true)).size());
    }

    @Test
    void paginatedListingStopsAfterFirstEnrichmentBatch(@TempDir Path home) throws Exception {
        String cwd = "/repo/main";
        SessionManager manager = new SessionManager(home, cwd);
        for (int i = 0; i < 100; i++) write(manager, "paged-" + i,
            "{\"type\":\"user\",\"message\":{\"content\":\"p" + i + "\"}}\n", i);
        AtomicInteger tasks = new AtomicInteger();
        SessionCatalog.IoObserver observer = new SessionCatalog.IoObserver() {
            @Override public void started() { tasks.incrementAndGet(); }
            @Override public void finished() {}
        };

        try (AutoCloseable ignored = SessionCatalog.observeIoForTest(observer)) {
            assertEquals(1, new SessionListingService(home).listSessions(
                new ListSessionsOptions(cwd, 1, 0, false, true)).size());
        }

        assertEquals(132, tasks.get(), "100 stat tasks plus one 32-file enrichment batch");
    }

    private static String write(SessionManager manager, String seed, String content, long mtime)
            throws Exception {
        String id = Strings.CS.contains(seed, "-") && seed.length() == 36
            ? seed : UUID.nameUUIDFromBytes(seed.getBytes()).toString();
        Path file = manager.getSessionFile(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
        return id;
    }
}
