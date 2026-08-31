package com.claudecode.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable hashing primitives shared by caches and on-disk identifiers. */
public final class HashUtils {
    private HashUtils() {}

    public static int djb2(String value) {
        int hash = 0;
        for (int i = 0; i < value.length(); i++) {
            hash = ((hash << 5) - hash) + value.charAt(i);
        }
        return hash;
    }

    public static String hashContent(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
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

    private static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
