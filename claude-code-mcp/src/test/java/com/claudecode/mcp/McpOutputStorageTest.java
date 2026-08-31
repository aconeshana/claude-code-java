package com.claudecode.mcp;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpOutputStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsRawBytesWithMimeDerivedExtension() throws Exception {
        byte[] bytes = "PDF data".getBytes(StandardCharsets.UTF_8);

        McpOutputStorage.PersistResult result = McpOutputStorage.persistBinaryContent(
            tempDir.resolve("tool-results"), bytes,
            "application/pdf; charset=binary", "mcp-report");

        assertTrue(result.succeeded());
        assertEquals(tempDir.resolve("tool-results/mcp-report.pdf"), result.filepath());
        assertEquals(bytes.length, result.size());
        assertEquals("pdf", result.extension());
        assertArrayEquals(bytes, Files.readAllBytes(result.filepath()));
    }

    @Test
    void mimeExtensionMappingMatchesSharedTsHelper() {
        assertEquals("json", McpOutputStorage.extensionForMimeType(
            "application/json; charset=utf-8"));
        assertEquals("docx", McpOutputStorage.extensionForMimeType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertEquals("jpg", McpOutputStorage.extensionForMimeType("image/jpeg"));
        assertEquals("bin", McpOutputStorage.extensionForMimeType("application/x-unknown"));
        assertEquals("bin", McpOutputStorage.extensionForMimeType(null));
    }

    @Test
    void writeFailureIsReturnedWithoutThrowing() {
        Path regularFile = tempDir.resolve("not-a-directory");
        try {
            Files.write(regularFile, new byte[0]);
        } catch (Exception error) {
            throw new AssertionError(error);
        }

        McpOutputStorage.PersistResult result = McpOutputStorage.persistBinaryContent(
            regularFile, new byte[] {1}, "application/pdf", "blob");

        assertFalse(result.succeeded());
        assertTrue(StringUtils.isNotBlank(result.error()));
    }
}
