package com.claudecode.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable hashing primitives shared by caches and on-disk identifiers. */
public final class HashUtils {
    private HashUtils() {}

    /**
     * Computes the 32-bit {@code 31 * h + c} polynomial hash (matching {@code ((h << 5) - h) + c}
     * with seed {@code 0} in upstream TypeScript cache/slug helpers). Note: distinct from
     * Bernstein's {@code 5381}/{@code 33} {@code djb2}.
     */
    public static int djb2(String value) {
        int hash = 0;
        for (int i = 0; i < value.length(); i++) {
            hash = ((hash << 5) - hash) + value.charAt(i);
        }
        return hash;
    }

    public static String hashContent(String content) {
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    public static String hashPair(String first, String second) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(first.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(second.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Lowercase hex-encoded SHA-256 digest of {@code input}. */
    public static String sha256Hex(byte[] input) {
        return HexFormat.of().formatHex(sha256Bytes(input));
    }

    /** Raw 32-byte SHA-256 digest of UTF-8 {@code content}. */
    public static byte[] sha256Bytes(String content) {
        return sha256Bytes(content.getBytes(StandardCharsets.UTF_8));
    }

    /** Raw 32-byte SHA-256 digest of {@code input}. */
    public static byte[] sha256Bytes(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
