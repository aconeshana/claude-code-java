package com.claudecode.mcp.oauth;

import org.apache.commons.lang3.Strings;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class PkceAndAuthorizeUrlTest {

    // ── PKCE ────────────────────────────────────────────────────────────────

    @Test
    void pkceGenerate_producesUrlSafeCharactersOnly() {
        PkcePair p = PkcePair.generate();
        // RFC 7636 §4.1: verifier chars are [A-Z] / [a-z] / [0-9] / '-' / '.' / '_' / '~'.
        // Our base64url no-padding output covers a strict subset (no '.' or '~').
        assertTrue(p.verifier().matches("^[A-Za-z0-9_-]+$"),
            "verifier must be base64url no-padding, got: " + p.verifier());
        assertTrue(p.challenge().matches("^[A-Za-z0-9_-]+$"),
            "challenge must be base64url no-padding, got: " + p.challenge());
    }

    @Test
    void pkceGenerate_verifierLengthWithinRfcBounds() {
        // 48 raw bytes → 64 base64url chars, well inside 43..128.
        PkcePair p = PkcePair.generate();
        assertTrue(p.verifier().length() >= 43 && p.verifier().length() <= 128,
            "verifier length out of RFC bounds: " + p.verifier().length());
    }

    @Test
    void pkceGenerate_producesDifferentPairsEachCall() {
        assertNotEquals(PkcePair.generate().verifier(), PkcePair.generate().verifier());
    }

    @Test
    void s256Challenge_isDeterministicForSameVerifier() {
        String v = "test-verifier-12345678901234567890123456789012345";
        assertEquals(PkcePair.s256Challenge(v), PkcePair.s256Challenge(v));
    }

    @Test
    void s256Challenge_matchesRfcAppendixBTestVector() {
        // RFC 7636 Appendix B verifier / challenge test vector.
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        assertEquals(expected, PkcePair.s256Challenge(verifier));
    }

    // ── Authorize URL ───────────────────────────────────────────────────────

    @Test
    void authorizeUrl_containsAllMandatoryParams() {
        var built = AuthorizeUrlBuilder.build(
            "https://as.example/authorize",
            "client-abc",
            "http://127.0.0.1:19876/callback",
            List.of("read", "write"),
            null);

        Map<String, String> q = parseQuery(built.url());
        assertEquals("code", q.get("response_type"));
        assertEquals("client-abc", q.get("client_id"));
        assertEquals("http://127.0.0.1:19876/callback", q.get("redirect_uri"));
        assertEquals("S256", q.get("code_challenge_method"));
        assertEquals(built.pkce().challenge(), q.get("code_challenge"));
        assertEquals(built.state(), q.get("state"));
        assertEquals("read write", q.get("scope"));
        assertFalse(q.containsKey("resource"));
    }

    @Test
    void authorizeUrl_appendsResourceParam_whenProvided() {
        var built = AuthorizeUrlBuilder.build(
            "https://as.example/authorize",
            "client-abc",
            "http://127.0.0.1:19876/callback",
            List.of("read"),
            "https://api.example/mcp");
        assertEquals("https://api.example/mcp", parseQuery(built.url()).get("resource"));
    }

    @Test
    void authorizeUrl_preservesExistingQueryString() {
        var built = AuthorizeUrlBuilder.build(
            "https://as.example/authorize?prompt=login",
            "client-abc",
            "http://127.0.0.1:19876/callback",
            List.of(),
            null);
        assertTrue(Strings.CS.contains(built.url(), "prompt=login"));
        assertTrue(Strings.CS.contains(built.url(), "&response_type=code"),
            "existing query should be preserved before appended params, got: " + built.url());
    }

    @Test
    void authorizeUrl_generatesFreshStateEachCall() {
        var a = AuthorizeUrlBuilder.build("https://as.example/authorize", "c", "http://127.0.0.1:1/cb",
            List.of(), null);
        var b = AuthorizeUrlBuilder.build("https://as.example/authorize", "c", "http://127.0.0.1:1/cb",
            List.of(), null);
        assertNotEquals(a.state(), b.state());
        assertNotEquals(a.pkce().verifier(), b.pkce().verifier());
    }

    @Test
    void authorizeUrl_rejectsBlankInputs() {
        assertThrows(IllegalArgumentException.class, () -> AuthorizeUrlBuilder.build(
            "", "c", "http://127.0.0.1:1/cb", List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> AuthorizeUrlBuilder.build(
            "https://as.example/authorize", "", "http://127.0.0.1:1/cb", List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> AuthorizeUrlBuilder.build(
            "https://as.example/authorize", "c", "", List.of(), null));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, String> parseQuery(String url) {
        URI u = URI.create(url);
        String q = u.getRawQuery();
        Map<String, String> out = new LinkedHashMap<>();
        if (q == null) return out;
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = URLDecoder.decode(part.substring(0, eq), UTF_8);
            String v = URLDecoder.decode(part.substring(eq + 1), UTF_8);
            out.put(k, v);
        }
        return out;
    }
}
