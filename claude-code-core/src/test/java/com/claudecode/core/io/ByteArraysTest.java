package com.claudecode.core.io;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ByteArraysTest {

    @Test
    void indexOfAndLastIndexOfSingleByte() {
        byte[] data = "a\nb\nc\n".getBytes(StandardCharsets.US_ASCII);
        assertEquals(1, ByteArrays.indexOf(data, (byte) '\n'));
        assertEquals(3, ByteArrays.indexOf(data, (byte) '\n', 2));
        assertEquals(1, ByteArrays.indexOf(data, (byte) '\n', -5));
        assertEquals(-1, ByteArrays.indexOf(data, (byte) 'z'));
        assertEquals(-1, ByteArrays.indexOf(null, (byte) 'a'));

        assertEquals(5, ByteArrays.lastIndexOf(data, (byte) '\n'));
        assertEquals(3, ByteArrays.lastIndexOf(data, (byte) '\n', 5));
        assertEquals(1, ByteArrays.lastIndexOf(data, (byte) '\n', 3));
        assertEquals(-1, ByteArrays.lastIndexOf(data, (byte) '\n', 1));
        assertEquals(-1, ByteArrays.lastIndexOf(null, (byte) '\n'));
    }

    @Test
    void lastIndexOfNeedle() {
        byte[] haystack = "foo bar foo baz".getBytes(StandardCharsets.US_ASCII);
        byte[] needle = "foo".getBytes(StandardCharsets.US_ASCII);
        assertEquals(8, ByteArrays.lastIndexOf(haystack, needle));
        assertEquals(-1, ByteArrays.lastIndexOf(haystack, "qux".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(-1, ByteArrays.lastIndexOf(haystack, new byte[0]));
        assertEquals(-1, ByteArrays.lastIndexOf(null, needle));
    }

    @Test
    void concatAndAsciiEquals() {
        byte[] combined = ByteArrays.concat(new byte[] {1, 2}, new byte[] {3, 4, 5});
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, combined);

        byte[] wav = "RIFF1234WAVE".getBytes(StandardCharsets.US_ASCII);
        assertTrue(ByteArrays.asciiEquals(wav, 0, "RIFF"));
        assertTrue(ByteArrays.asciiEquals(wav, 8, "WAVE"));
        assertFalse(ByteArrays.asciiEquals(wav, 9, "WAVE"));
        assertFalse(ByteArrays.asciiEquals(wav, -1, "RIFF"));
    }

    @Test
    void binaryIntegerReaders() {
        byte[] bytes = new byte[] {0x12, 0x34, 0x56, (byte) 0x80};
        assertEquals(0x1234, ByteArrays.readUnsignedShortBE(bytes, 0));
        assertEquals(0x3412, ByteArrays.readUnsignedShortLE(bytes, 0));
        assertEquals(0x563412, ByteArrays.readUnsignedMediumLE(bytes, 0));
        assertEquals(0x12345680, ByteArrays.readIntBE(bytes, 0));
        assertEquals(0x80563412, ByteArrays.readIntLE(bytes, 0));
        assertEquals(0x80563412L, ByteArrays.readUnsignedIntLE(bytes, 0));
    }
}
