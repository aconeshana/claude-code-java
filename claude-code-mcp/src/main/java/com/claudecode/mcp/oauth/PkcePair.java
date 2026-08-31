package com.claudecode.mcp.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE (RFC 7636) code_verifier / code_challenge helper.
 */
public record PkcePair(String verifier, String challenge) {

    // RFC 7636 §4.1: verifier length is 43..128 characters. We pick 64 —
    // decodes back to 48 raw bytes of entropy, plenty against brute force.
    private static final int VERIFIER_BYTES = 48;
    private static final SecureRandom RNG = new SecureRandom();

    /**
     * Generates a fresh (verifier, S256(challenge)) pair.
     */
    public static PkcePair generate() {
        byte[] raw = new byte[VERIFIER_BYTES];
        RNG.nextBytes(raw);
        String verifier = base64UrlNoPadding(raw);
        String challenge = s256Challenge(verifier);
        return new PkcePair(verifier, challenge);
    }

    /**
     * Computes {@code BASE64URL(SHA256(ASCII(verifier)))} — the S256 code
     * challenge shape mandated by RFC 7636 §4.2.
     */
    public static String s256Challenge(String verifier) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return base64UrlNoPadding(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JCA algorithm — if it's missing, the JVM is broken.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String base64UrlNoPadding(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
