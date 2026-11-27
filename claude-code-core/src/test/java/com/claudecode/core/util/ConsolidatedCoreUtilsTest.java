package com.claudecode.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.error.ErrorUtils;
import com.claudecode.core.io.IoUtils;
import com.claudecode.core.io.PathUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.text.StringUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConsolidatedCoreUtilsTest {

    @Test
    void mathUtilsSaturatedAddClampsBothBounds() {
        assertEquals(15L, MathUtils.saturatedAdd(7L, 8L));
        assertEquals(Long.MAX_VALUE, MathUtils.saturatedAdd(Long.MAX_VALUE - 2L, 10L));
        assertEquals(Long.MIN_VALUE, MathUtils.saturatedAdd(Long.MIN_VALUE + 2L, -10L));
    }

    @Test
    void ioUtilsCloseAndCancelAreNullAndFailureSafe() {
        AtomicBoolean closed = new AtomicBoolean();
        IoUtils.closeQuietly(null);
        assertDoesNotThrow(() -> IoUtils.closeQuietly(() -> {
            closed.set(true);
            throw new IOException("boom");
        }));
        assertTrue(closed.get());

        IoUtils.cancelQuietly(null);
        CompletableFuture<Void> future = new CompletableFuture<>();
        IoUtils.cancelQuietly(future);
        assertTrue(future.isCancelled());
    }

    @Test
    void collectionUtilsCreatesNullSafeUnmodifiableCopy() {
        assertEquals(List.of(), CollectionUtils.immutableList(null));
        List<String> mutable = new ArrayList<>(List.of("a", "b"));
        List<String> copy = CollectionUtils.immutableList(mutable);
        mutable.add("c");
        assertEquals(List.of("a", "b"), copy);
        assertThrows(UnsupportedOperationException.class, () -> copy.add("d"));
    }

    @Test
    void pathUtilsBasenameAndExtensionOrNullHandleEdgeCases() {
        assertNull(PathUtils.basename(null));
        assertEquals("vim", PathUtils.basename("/usr/bin/vim"));
        assertEquals("code.exe", PathUtils.basename("C:\\Program Files\\VS Code\\code.exe"));
        assertEquals("plain", PathUtils.basename("plain"));

        assertEquals(".ts", PathUtils.extensionOrNull(Path.of("dir/app.TS")));
        assertNull(PathUtils.extensionOrNull(Path.of("Makefile")));
        assertNull(PathUtils.extensionOrNull(Path.of(".gitignore")));
        assertNull(PathUtils.extensionOrNull(Path.of("trailing.")));
    }

    @Test
    void stringUtilsJavaScriptWhitespaceMatchesEcmaTrim() {
        assertEquals("hello", StringUtils.stripJavaScriptWhitespace("\u00A0\u3000\t hello \uFEFF\n"));
        assertFalse(StringUtils.isJavaScriptNonBlank("\u00A0\u2003\uFEFF"));
        assertTrue(StringUtils.isJavaScriptNonBlank("\u00A0x"));
    }

    @Test
    void formatGroupedNumberAndJsonNodeHelpersBehaveConsistently() {
        assertEquals("1,234,567", FormatUtils.formatGroupedNumber(1_234_567L));

        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        JsonUtils.putIfNotBlank(node, "skip", "   ");
        JsonUtils.putIfNotBlank(node, "keep", "ok");
        node.putNull("nil");
        assertNull(JsonUtils.textOrNull(node, "skip"));
        assertNull(JsonUtils.textOrNull(node, "nil"));
        assertEquals("ok", JsonUtils.textOrNull(node, "keep"));
    }

    @Test
    void errorUtilsCoversEpermAndRootCauseUnwrapping() {
        assertTrue(ErrorUtils.isFsInaccessible(
            new FileSystemException("/tmp/x", null, "Operation not permitted")));
        RuntimeException leaf = new IllegalStateException("rootboom");
        RuntimeException wrapped = new RuntimeException("outer", leaf);
        assertEquals("rootboom", ErrorUtils.rootCauseMessage(wrapped));
        assertEquals("IllegalArgumentException",
            ErrorUtils.rootCauseMessage(new RuntimeException(new IllegalArgumentException())));
    }

    @Test
    void hashUtilsExposesByteAndHexSha256() {
        byte[] abc = "abc".getBytes(StandardCharsets.UTF_8);
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HashUtils.sha256Hex(abc));
        assertArrayEquals(HashUtils.sha256Bytes("abc"), HashUtils.sha256Bytes(abc));
    }
}
