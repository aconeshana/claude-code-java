package com.claudecode.core.io;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileUtilsTest {

    @TempDir
    Path tmp;

    @Test
    void writeStringCreatesParentDirs() throws IOException {
        Path file = tmp.resolve("a/b/c/out.txt");
        FileUtils.writeString(file, "hello");
        assertTrue(Files.exists(file));
        assertEquals("hello", Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(tmp.resolve("a/b/c")));
    }

    @Test
    void writeStringOverwritesExistingContent() throws IOException {
        Path file = tmp.resolve("overwrite.txt");
        FileUtils.writeString(file, "first");
        FileUtils.writeString(file, "second");
        assertEquals("second", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void writeStringWithCharsetPreservesBytes() throws IOException {
        Path file = tmp.resolve("latin.txt");
        FileUtils.writeString(file, "café", StandardCharsets.ISO_8859_1);
        assertEquals("café", Files.readString(file, StandardCharsets.ISO_8859_1));
    }

    @Test
    void writeBytesCreatesParentAndWrites() throws IOException {
        Path file = tmp.resolve("d/nested.bin");
        byte[] data = {1, 2, 3, 4};
        FileUtils.writeBytes(file, data);
        assertArrayEquals(data, Files.readAllBytes(file));
    }

    @Test
    void atomicReplacePublishesCompleteContentAndCreatesParent() throws IOException {
        Path file = tmp.resolve("atomic/nested/value.txt");

        FileUtils.atomicReplace(file, temp -> Files.writeString(temp, "complete"));

        assertEquals("complete", Files.readString(file));
        assertEquals(List.of(file), Files.list(file.getParent()).toList());
    }

    @Test
    void atomicReplaceWritesThroughFinalSymlinkAndPreservesPosixMode() throws IOException {
        Path target = tmp.resolve("settings-target.json");
        Path link = tmp.resolve("settings.json");
        Files.writeString(target, "old");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "filesystem does not support test symlinks: " + e);
            return;
        }

        Set<PosixFilePermission> originalMode = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ);
        if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(target, originalMode);
        }

        FileUtils.atomicReplace(link, temp -> Files.writeString(temp, "new"));

        assertTrue(Files.isSymbolicLink(link));
        assertEquals("new", Files.readString(target));
        if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(originalMode, Files.getPosixFilePermissions(target));
        }
    }

    @Test
    void atomicReplacePreservesExistingTargetWhenWriterFails() throws IOException {
        Path file = tmp.resolve("atomic-failure/value.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "original");

        assertThrows(IOException.class, () -> FileUtils.atomicReplace(file, temp -> {
            Files.writeString(temp, "partial");
            throw new IOException("boom");
        }));

        assertEquals("original", Files.readString(file));
        assertEquals(List.of(file), Files.list(file.getParent()).toList());
    }

    @Test
    void writeFullyDrainsByteBuffer() throws IOException {
        Path file = tmp.resolve("fully.bin");
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});

        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileUtils.writeFully(channel, buffer);
        }

        assertEquals(0, buffer.remaining());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(file));
    }

    @Test
    void writeFullyWritesOnlyTheBuffersRemainingSlice() throws IOException {
        Path file = tmp.resolve("remaining.bin");
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {9, 1, 2, 3});
        buffer.position(1);

        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileUtils.writeFully(channel, buffer);
        }

        assertEquals(0, buffer.remaining());
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(file));
    }

    @Test
    void trySetOwnerOnlyPermissionsUses0600OnPosix() throws IOException {
        Path file = tmp.resolve("private.json");
        Files.writeString(file, "{}");

        FileUtils.trySetOwnerOnlyPermissions(file);

        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file));
        }
    }

    @Test
    void copyFileCopiesAndReplaces() throws IOException {
        Path src = tmp.resolve("src.txt");
        Files.writeString(src, "data");
        Path dest = tmp.resolve("nested/dest.txt");
        FileUtils.copyFile(src, dest);
        assertEquals("data", Files.readString(dest, StandardCharsets.UTF_8));

        Files.writeString(src, "updated");
        FileUtils.copyFile(src, dest);
        assertEquals("updated", Files.readString(dest, StandardCharsets.UTF_8));
    }

    @Test
    void copyDirectoryReplicatesTree() throws IOException {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("sub"));
        Files.writeString(src.resolve("a.txt"), "a");
        Files.writeString(src.resolve("sub/b.txt"), "b");

        Path dest = tmp.resolve("dest");
        FileUtils.copyDirectory(src, dest);

        assertTrue(Files.isRegularFile(dest.resolve("a.txt")));
        assertTrue(Files.isRegularFile(dest.resolve("sub/b.txt")));
        assertEquals("a", Files.readString(dest.resolve("a.txt"), StandardCharsets.UTF_8));
        assertEquals("b", Files.readString(dest.resolve("sub/b.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void copyDirectoryReplacesExistingFiles() throws IOException {
        Path src = tmp.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("f.txt"), "v2");

        Path dest = tmp.resolve("dest");
        Files.createDirectories(dest);
        Files.writeString(dest.resolve("f.txt"), "v1");

        FileUtils.copyDirectory(src, dest);
        assertEquals("v2", Files.readString(dest.resolve("f.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void stripExtensionPreservesDotfiles() {
        assertEquals("README", FileUtils.stripExtension("README.md"));
        assertEquals(".gitignore", FileUtils.stripExtension(".gitignore"));
        assertEquals("noext", FileUtils.stripExtension("noext"));
        assertEquals("archive.tar", FileUtils.stripExtension("archive.tar.gz"));
    }

    @Test
    void stripSuffixIsCaseInsensitiveAndSuffixSpecific() {
        assertEquals("README", FileUtils.stripSuffix("README.md", ".md"));
        assertEquals("foo", FileUtils.stripSuffix("foo.MD", ".md"));
        assertEquals("a.txt", FileUtils.stripSuffix("a.txt", ".md"));
        assertEquals("only.md", FileUtils.stripSuffix("only.md.md", ".md"));
        assertEquals("only.md.bak", FileUtils.stripSuffix("only.md.bak", ".md"));
    }

    @Test
    void listFilesRespectsGlob() throws IOException {
        Files.writeString(tmp.resolve("a.json"), "{}");
        Files.writeString(tmp.resolve("b.json"), "{}");
        Files.writeString(tmp.resolve("c.md"), "#");
        Files.writeString(tmp.resolve("d.txt"), "x");

        List<Path> json = FileUtils.listFiles(tmp, "*.json");
        assertEquals(2, json.size());

        List<Path> md = FileUtils.listFiles(tmp, "*.md");
        assertEquals(1, md.size());
    }

    @Test
    void listFilesWithPredicateFilters() throws IOException {
        Files.writeString(tmp.resolve("a.json"), "{}");
        Files.writeString(tmp.resolve("b.md"), "#");
        List<Path> regular = FileUtils.listFiles(tmp, Files::isRegularFile);
        assertTrue(regular.size() >= 2);
    }

    @Test
    void createTempFileAndDirSucceed() throws IOException {
        Path f = FileUtils.createTempFile("cc-test-", ".txt");
        Path d = FileUtils.createTempDir("cc-test-dir-");
        assertTrue(Files.isRegularFile(f));
        assertTrue(Files.isDirectory(d));
    }

    @Test
    void existenceGuardsHandleNull() {
        assertFalse(FileUtils.exists(null));
        assertFalse(FileUtils.isRegularFile(null));
    }

    @Test
    void rangeTailAndReverseReadsShareUtf8FileSemantics() throws IOException {
        Path file = tmp.resolve("range.txt");
        Files.writeString(file, "one\ntwo\n三\n");
        FileUtils.FileRange head = FileUtils.readFileRange(file, 0, 3);
        assertEquals("one", head.content());
        assertEquals(3, head.bytesRead());
        FileUtils.FileRange tail = FileUtils.tailFile(file, 4);
        assertTrue(Strings.CS.endsWith(tail.content(), "三\n"));
        assertEquals(List.of("三", "two"), FileUtils.readLinesReverse(file, 2));
    }

    @Test
    void reverseReadPreservesUtf8SplitAcrossChunkBoundaryAndStopsAtLimit() throws IOException {
        Path file = tmp.resolve("reverse-chunks.txt");
        String boundaryLine = "三" + "x".repeat(4087);
        Files.writeString(file, "old\n" + boundaryLine + "\nlatest\n");

        assertEquals(List.of("latest", boundaryLine), FileUtils.readLinesReverse(file, 2));
    }

    @Test
    void resolvesNonexistentTailBelowSymlinkForPermissionChecks() throws IOException {
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Path link = tmp.resolve("link");
        Files.createSymbolicLink(link, outside);
        Path requested = link.resolve("new/file.txt");
        assertTrue(FileUtils.pathsForPermissionCheck(requested)
            .contains(outside.resolve("new/file.txt")));
    }

    @Test
    void duplicatePathUsesCanonicalSymlinkTarget() throws IOException {
        Path target = Files.writeString(tmp.resolve("target.md"), "body");
        Path alias = tmp.resolve("alias.md");
        Files.createSymbolicLink(alias, target.getFileName());
        Set<Path> loaded = new java.util.HashSet<>();

        assertFalse(FileUtils.isDuplicatePath(alias, loaded));
        assertTrue(FileUtils.isDuplicatePath(target, loaded));
    }

    @Test
    void modificationTimeMillisMatchesNioMillisecondTimestamp() throws IOException {
        Path file = Files.writeString(tmp.resolve("mtime.txt"), "body");

        assertEquals(Files.getLastModifiedTime(file).toMillis(),
            FileUtils.modificationTimeMillis(file));
    }

    @Test
    void deleteRecursivelyRemovesTreeAndIsIdempotent() throws IOException {
        Path tree = tmp.resolve("tree");
        Files.createDirectories(tree.resolve("sub"));
        Files.writeString(tree.resolve("sub/x.txt"), "x");
        assertTrue(Files.exists(tree));

        FileUtils.deleteRecursively(tree);
        assertFalse(Files.exists(tree));

        // Idempotent on a missing path.
        FileUtils.deleteRecursively(tree);
        assertFalse(Files.exists(tree));
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length, "array length");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "byte at " + i);
        }
    }
}
