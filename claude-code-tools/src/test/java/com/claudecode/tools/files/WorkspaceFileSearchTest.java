package com.claudecode.tools.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shared workspace enumeration and ranking behind both the TUI typeahead
 * and the webui {@code @} menu.
 */
class WorkspaceFileSearchTest {

    private final WorkspaceFileSearch search = new WorkspaceFileSearch();

    @Test
    void blankQueryListsTheWorkingDirectorysTopLevel() throws IOException {
        Path root = Files.createTempDirectory("wfs-top");
        try {
            Files.createDirectories(root.resolve("src"));
            Files.writeString(root.resolve("README.md"), "hi");
            List<WorkspaceFileSearch.Candidate> rows = search.suggest(root.toString(), "", 50);
            assertThat(rows).extracting(WorkspaceFileSearch.Candidate::path)
                .containsExactlyInAnyOrder("src/", "README.md");
            // A directory row keeps the trailing slash the client renders and
            // the pick inserts; that is what makes the drill work by typing.
            assertThat(rows.stream().filter(r -> r.path().equals("src/")).findFirst()
                .orElseThrow().directory()).isTrue();
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * A drilled listing returns each row as the <em>full</em> path, not the
     * bare entry name. The client inserts the row verbatim as an `@path`
     * mention, so a bare `brand/` would resolve against the workspace root
     * instead of the walked directory and the model would never see the file.
     */
    @Test
    void pathLikeQueryListsThatDirectoryPrefixFiltered() throws IOException {
        Path root = Files.createTempDirectory("wfs-dir");
        try {
            Files.createDirectories(root.resolve("src/main"));
            Files.createDirectories(root.resolve("script"));
            Files.writeString(root.resolve("src/app.ts"), "x");
            String query = root + "/s";
            List<WorkspaceFileSearch.Candidate> rows = search.suggest("/", query, 50);
            assertThat(rows).extracting(WorkspaceFileSearch.Candidate::path)
                .containsExactlyInAnyOrder(root + "/src/", root + "/script/");
        } finally {
            deleteRecursively(root);
        }
    }

    /** A nested drill keeps the whole typed directory prefix on every row. */
    @Test
    void nestedDrillKeepsTheTypedPrefix() throws IOException {
        Path root = Files.createTempDirectory("wfs-nested");
        try {
            Files.createDirectories(root.resolve("webui/src/views/brand"));
            Files.writeString(root.resolve("webui/src/views/App.tsx"), "x");
            String query = root + "/webui/src/views/";
            List<WorkspaceFileSearch.Candidate> rows = search.suggest("/", query, 50);
            assertThat(rows).extracting(WorkspaceFileSearch.Candidate::path)
                .containsExactlyInAnyOrder(
                    query + "brand/", query + "App.tsx");
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void pathLikeDetectsRootedAndRelativeQueries() {
        assertThat(WorkspaceFileSearch.isPathLike("~/x")).isTrue();
        assertThat(WorkspaceFileSearch.isPathLike("~")).isTrue();
        assertThat(WorkspaceFileSearch.isPathLike("/etc")).isTrue();
        assertThat(WorkspaceFileSearch.isPathLike("./x")).isTrue();
        assertThat(WorkspaceFileSearch.isPathLike("../x")).isTrue();
        assertThat(WorkspaceFileSearch.isPathLike("src/main")).isTrue();
        assertThat(WorkspaceFileSearch.isPathLike("README")).isFalse();
    }

    /** The ranking contract both surfaces share. */
    @Test
    void rankingPrefersAnExactLeadingSegmentAndAPrefixBasename() {
        // A query matching the whole first segment outranks a scattered match.
        assertThat(WorkspaceFileSearch.subsequenceScore("src", "src/main/App.java"))
            .isGreaterThan(WorkspaceFileSearch.subsequenceScore("src", "some/other/rc.java"));
        // A basename prefix carries its bonus.
        assertThat(WorkspaceFileSearch.subsequenceScore("app", "src/App.java"))
            .isGreaterThan(WorkspaceFileSearch.subsequenceScore("app", "src/zzzAppzzz.java"));
        // Not a subsequence at all.
        assertThat(WorkspaceFileSearch.subsequenceScore("zzz", "src/App.java")).isNegative();
        // An empty query matches everything with the neutral score.
        assertThat(WorkspaceFileSearch.subsequenceScore("", "anything")).isZero();
    }

    @Test
    void limitCapsTheRows() throws IOException {
        Path root = Files.createTempDirectory("wfs-limit");
        try {
            for (int i = 0; i < 10; i++) Files.writeString(root.resolve("f" + i + ".txt"), "x");
            assertThat(search.suggest(root.toString(), "", 3)).hasSize(3);
            // A non-positive limit is the unlimited request.
            assertThat(search.suggest(root.toString(), "", 0)).hasSize(10);
        } finally {
            deleteRecursively(root);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException _) {
                    // Best-effort cleanup of a temp fixture.
                }
            });
        }
    }
}