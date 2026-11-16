package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.session.FileProjectIndexStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The listing for a gateway that serves no catalog of its own — the standalone
 * {@code claude web} process — answered from the configuration home's
 * transcripts.
 *
 * <p>Without it the listing held live sessions only, so two things a webui
 * client had just done were invisible in the very next listing: a fork (a
 * transcript on disk with no owning process) never appeared, and a rename or an
 * archive — both of which only append a row to a transcript — left no trace
 * because nothing read that transcript.
 */
class TranscriptSessionCatalogTest {

    private Path home;
    private String sourceId;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createTempDirectory(Path.of("/tmp"), "tsc");
        sourceId = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteTree(home);
    }

    @Test
    void listsASessionThatExistsOnlyOnDisk() throws IOException {
        writeTranscript(sourceId, "-work");

        List<GatewaySessionCatalogPort.ProjectEntry> projects =
            catalog().listProjects();

        assertThat(ids(projects)).containsExactly(sourceId);
    }

    @Test
    void reportsTheCustomTitleTheTranscriptsLastRowCarries() throws IOException {
        Path transcript = writeTranscript(sourceId, "-work");
        Files.writeString(transcript,
            "{\"type\":\"custom-title\",\"customTitle\":\"Renamed by web\",\"sessionId\":\""
                + sourceId + "\"}\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.APPEND);

        List<GatewaySessionCatalogPort.ProjectEntry> projects =
            catalog().listProjects();

        assertThat(projects.getFirst().sessions().getFirst().customTitle())
            .isEqualTo("Renamed by web");
    }

    @Test
    void dropsASessionTheTranscriptMarksArchived() throws IOException {
        Path transcript = writeTranscript(sourceId, "-work");
        Files.writeString(transcript,
            "{\"type\":\"archived\",\"archived\":true,\"sessionId\":\"" + sourceId + "\"}\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.APPEND);

        assertThat(ids(catalog().listProjects())).isEmpty();
    }

    @Test
    void picksUpATranscriptWrittenAfterAnEarlierListing() throws IOException {
        TranscriptSessionCatalog catalog = catalog();
        assertThat(catalog.listProjects()).isEmpty();

        String forked = writeTranscript(UUID.randomUUID().toString(), "-work").getFileName()
            .toString().replace(".jsonl", "");

        assertThat(ids(catalog.listProjects())).containsExactly(forked);
    }

    // ------------------------------------------------------------ decoration

    @Test
    void leavesACatalogThatAlreadyServesAListingAlone() {
        GatewaySessionCatalogPort configured = catalogOf(sourceId);

        assertThat(TranscriptSessionCatalog.decorate(configured, home)).isSameAs(configured);
    }

    @Test
    void replacesACatalogThatAnswersNothing() throws IOException {
        writeTranscript(sourceId, "-work");

        // Built through the same-thread seam: the production constructor this
        // decoration would call writes its index from a background virtual
        // thread, which outlives the assertion and races the temp-dir cleanup.
        GatewaySessionCatalogPort decorated = TranscriptSessionCatalog.decorate(
            catalogOf(), home, _ -> catalog());

        assertThat(decorated.listProjects().getFirst().sessions().getFirst().id())
            .isEqualTo(sourceId);
    }

    // ------------------------------------------------------------- fixtures

    /** Distinct ids in listing order, flattened across projects. */
    private static List<String> ids(List<GatewaySessionCatalogPort.ProjectEntry> projects) {
        return projects.stream()
            .flatMap(project -> project.sessions().stream())
            .map(GatewaySessionCatalogPort.SessionEntry::id)
            .toList();
    }

    private static GatewaySessionCatalogPort catalogOf(String... sessionIds) {
        return new GatewaySessionCatalogPort() {
            @Override public List<ProjectEntry> listProjects() {
                if (sessionIds.length == 0) return List.of();
                return List.of(new ProjectEntry("/work", "work", sessionIds.length, 0,
                    Stream.of(sessionIds)
                        .map(id -> new SessionEntry(id, "summary", 2, 0, "", "/work", "", ""))
                        .toList()));
            }
        };
    }

    private Path writeTranscript(String sessionId, String dirName) throws IOException {
        Path project = Files.createDirectories(home.resolve("projects").resolve(dirName));
        Path transcript = project.resolve(sessionId + ".jsonl");
        String user = UUID.randomUUID().toString();
        Files.writeString(transcript,
            "{\"type\":\"user\",\"uuid\":\"" + user + "\",\"parentUuid\":null,\"sessionId\":\""
                + sessionId + "\",\"cwd\":\"/work\",\"message\":{\"role\":\"user\","
                + "\"content\":\"hello\"},\"timestamp\":\"2026-01-01T00:00:00Z\"}\n",
            StandardCharsets.UTF_8);
        return transcript;
    }

    /** Same-thread persistence: the catalog otherwise flushes on a virtual thread. */
    private TranscriptSessionCatalog catalog() {
        return new TranscriptSessionCatalog(home,
            new FileProjectIndexStore(home.resolve("cache").resolve("project-index.json")),
            Runnable::run);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
