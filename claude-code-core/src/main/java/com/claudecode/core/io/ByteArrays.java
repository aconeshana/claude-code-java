package com.claudecode.core.io;

import java.util.Arrays;

/**
 * Pure in-memory {@code byte[]} search, concatenation, and binary integer decoding helpers
 * shared across modules.
 */
public final class ByteArrays {

    private ByteArrays() {}

    /** Returns the first index of {@code target} in {@code bytes}, or {@code -1} if absent. */
    public static int indexOf(byte[] bytes, byte target) {
        return indexOf(bytes, target, 0);
    }

    /**
     * Returns the first index {@code >= max(0, fromIndex)} of {@code target} in {@code bytes},
     * or {@code -1} if absent.
     */
    public static int indexOf(byte[] bytes, byte target, int fromIndex) {
        if (bytes == null) return -1;
        for (int i = Math.max(0, fromIndex); i < bytes.length; i++) {
            if (bytes[i] == target) return i;
        }
        return -1;
    }

    /** Returns the last index of {@code target} in {@code bytes}, or {@code -1} if absent. */
    public static int lastIndexOf(byte[] bytes, byte target) {
        return bytes == null ? -1 : lastIndexOf(bytes, target, bytes.length);
    }

    /**
     * Returns the last index strictly before {@code beforeIndex} of {@code target} in {@code bytes},
     * or {@code -1} if absent.
     */
    public static int lastIndexOf(byte[] bytes, byte target, int beforeIndex) {
        if (bytes == null) return -1;
        for (int i = Math.min(beforeIndex - 1, bytes.length - 1); i >= 0; i--) {
            if (bytes[i] == target) return i;
        }
        return -1;
    }

    /**
     * Returns the starting index of the last occurrence of {@code needle} in {@code haystack},
     * or {@code -1} if not found (or if either array is null / {@code needle} is empty).
     */
    public static int lastIndexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || needle.length > haystack.length) {
            return -1;
        }
        for (int i = haystack.length - needle.length; i >= 0; i--) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j]) j++;
            if (j == needle.length) return i;
        }
        return -1;
    }

    /** Concatenates two byte arrays into a new array. */
    public static byte[] concat(byte[] first, byte[] second) {
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    /** Returns {@code true} if {@code bytes} matches the ASCII characters of {@code value} at {@code offset}. */
    public static boolean asciiEquals(byte[] bytes, int offset, String value) {
        if (bytes == null || value == null || offset < 0 || offset + value.length() > bytes.length) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (bytes[offset + i] != (byte) value.charAt(i)) return false;
        }
        return true;
    }

    /** Reads an unsigned 16-bit big-endian integer at {@code offset}. */
    public static int readUnsignedShortBE(byte[] bytes, int offset) {
        return (Byte.toUnsignedInt(bytes[offset]) << 8)
            | Byte.toUnsignedInt(bytes[offset + 1]);
    }

    /** Reads an unsigned 16-bit little-endian integer at {@code offset}. */
    public static int readUnsignedShortLE(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
            | (Byte.toUnsignedInt(bytes[offset + 1]) << 8);
    }

    /** Reads an unsigned 24-bit little-endian integer at {@code offset}. */
    public static int readUnsignedMediumLE(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
            | (Byte.toUnsignedInt(bytes[offset + 1]) << 8)
            | (Byte.toUnsignedInt(bytes[offset + 2]) << 16);
    }

    /** Reads a signed 32-bit big-endian integer at {@code offset}. */
    public static int readIntBE(byte[] bytes, int offset) {
        return (Byte.toUnsignedInt(bytes[offset]) << 24)
            | (Byte.toUnsignedInt(bytes[offset + 1]) << 16)
            | (Byte.toUnsignedInt(bytes[offset + 2]) << 8)
            | Byte.toUnsignedInt(bytes[offset + 3]);
    }

    /** Reads a signed 32-bit little-endian integer at {@code offset}. */
    public static int readIntLE(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
            | (Byte.toUnsignedInt(bytes[offset + 1]) << 8)
            | (Byte.toUnsignedInt(bytes[offset + 2]) << 16)
            | (Byte.toUnsignedInt(bytes[offset + 3]) << 24);
    }

    /** Reads an unsigned 32-bit little-endian integer at {@code offset}. */
    public static long readUnsignedIntLE(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(readIntLE(bytes, offset));
    }
}
