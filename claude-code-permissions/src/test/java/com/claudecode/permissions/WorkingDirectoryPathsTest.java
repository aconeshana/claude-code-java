package com.claudecode.permissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link WorkingDirectoryPaths}. */
class WorkingDirectoryPathsTest {

    @TempDir Path temp;

    @Test
    void allWorkingDirectoriesIncludesWorkingDirAndAdditionalDirs() {
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .additionalDirs(Map.of(Path.of(System.getProperty("java.io.tmpdir")), RuleSource.SESSION))
            .build();

        Set<Path> dirs = WorkingDirectoryPaths.allWorkingDirectories(ctx);
        assertEquals(2, dirs.size());
        assertTrue(dirs.contains(Path.of(".").toAbsolutePath().normalize()));
    }

    @Test
    void childPathIsWithinWorkingDirectory() {
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        Path child = cwd.resolve("pom.xml");
        assertTrue(WorkingDirectoryPaths.isWithin(child, cwd));
    }

    @Test
    void samePathIsWithinItself() {
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        assertTrue(WorkingDirectoryPaths.isWithin(cwd, cwd));
    }

    @Test
    void siblingDirectorySharingStringPrefixIsNotWithin() {
        // "/foo-bar" must not be considered inside "/foo" just because it shares
        // a string prefix — Path.startsWith compares path segments, not chars.
        assertFalse(WorkingDirectoryPaths.isWithin(Path.of("/foo-bar/file.txt"), Path.of("/foo")));
    }

    @Test
    void unrelatedPathIsNotWithin() {
        assertFalse(WorkingDirectoryPaths.isWithin(Path.of("/etc/hosts"), Path.of("/home/user/project")));
    }

    @Test
    void isWithinWorkingDirectoriesChecksAdditionalDirsToo() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        ToolPermissionContext ctx = ToolPermissionContext.builder()
            .workingDirectory(Path.of("/some/unrelated/project"))
            .additionalDirs(Map.of(tmp, RuleSource.SESSION))
            .build();

        assertTrue(WorkingDirectoryPaths.isWithinWorkingDirectories(tmp.resolve("file.txt"), ctx));
        assertFalse(WorkingDirectoryPaths.isWithinWorkingDirectories(Path.of("/etc/hosts"), ctx));
    }

    @Test
    void macOsPrivateVarAliasIsNormalized() {

        // them via literal string replacement, not real symlink resolution.
        assertTrue(WorkingDirectoryPaths.isWithin(
            Path.of("/private/var/folders/x/file.txt"), Path.of("/var/folders/x")));
    }

    @Test
    void macOsPrivateTmpAliasIsNormalized() {
        assertTrue(WorkingDirectoryPaths.isWithin(
            Path.of("/private/tmp/file.txt"), Path.of("/tmp")));
    }

    @Test
    void parentSymlinkCannotEscapeWorkingDirectoryForNewFile() throws Exception {
        Path project = temp.resolve("project");
        Path outside = temp.resolve("outside");
        Files.createDirectories(project);
        Files.createDirectories(outside);
        Files.createSymbolicLink(project.resolve("data"), outside);
        ToolPermissionContext ctx = ToolPermissionContext.builder().workingDirectory(project).build();

        assertFalse(WorkingDirectoryPaths.isWithinWorkingDirectories(
            project.resolve("data/new-file.txt"), ctx));
    }
}
