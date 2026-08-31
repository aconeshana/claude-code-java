package com.claudecode.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class HashUtilsTest {
    @Test
    void matchesStableTsFallbacks() {
        assertEquals(96354, HashUtils.djb2("abc"));
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HashUtils.hashContent("abc"));
        assertEquals(
            "59b271ae1bbcb1d31d41929817f4b16fb439eb4f31520b5ad1d5ce98920a7138",
            HashUtils.hashPair("a", "b"));
    }

    @Test
    void pairSeparatorPreventsConcatenationAmbiguity() {
        Assertions.assertNotEquals(
            HashUtils.hashPair("ts", "code"), HashUtils.hashPair("tsc", "ode"));
    }
}
