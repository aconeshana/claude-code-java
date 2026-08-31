package com.claudecode.mcp.oauth;

import org.apache.commons.lang3.Strings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlaintextSecureStorageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    void read_returnsEmpty_whenFileMissing() {
        PlaintextSecureStorage s = new PlaintextSecureStorage(tempDir);
        assertTrue(s.read().isEmpty());
    }

    @Test
    void update_thenRead_roundTripsMcpOAuthEntry() {
        PlaintextSecureStorage s = new PlaintextSecureStorage(tempDir);
        var entry = new SecureStorageData.McpOAuthEntry(
            "github", "https://api.githubcopilot.com/mcp/",
            "client-abc", null,
            "at-token", "rt-token", 1_700_000_000_000L,
            "https://as.example/token", "read");
        var data = new SecureStorageData(Map.of("github#hash", entry), null, null);

        var warn = s.update(data);
        assertTrue(warn.isPresent());
        assertTrue(Strings.CS.contains(warn.get(), "plaintext"));

        SecureStorageData read = s.read().orElseThrow();
        SecureStorageData.McpOAuthEntry back = read.mcpOAuth().get("github#hash");
        assertNotNull(back);
        assertEquals("github",              back.serverName());
        assertEquals("client-abc",          back.clientId());
        assertEquals("at-token",            back.accessToken());
        assertEquals("rt-token",            back.refreshToken());
        assertEquals(1_700_000_000_000L,    back.expiresAt());
        assertEquals("read",                back.scope());
    }

    @Test
    void update_setsPosixOwnerOnlyPermissions() throws IOException {
        PlaintextSecureStorage s = new PlaintextSecureStorage(tempDir);
        s.update(SecureStorageData.empty());

        Path file = s.filePathForTest();
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return; // Windows: no POSIX perms to check
        }
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            perms, "credentials file must be 0600");
    }

    @Test
    void update_preservesForeignTopLevelFields() throws IOException {

        // Anthropic API key entry — we must not clobber it.
        Path credFile = tempDir.resolve(".credentials.json");
        Files.writeString(credFile, """
            {
              "anthropicApiKey": "sk-ant-test",
              "mcpOAuth": {}
            }
            """);

        PlaintextSecureStorage s = new PlaintextSecureStorage(tempDir);
        var data = s.read().orElseThrow();
        data.mcpOAuth().put("srv#h", new SecureStorageData.McpOAuthEntry(
            "srv", "https://x", null, null, "at", null, 0L, null, null));
        s.update(data);

        JsonNode root = MAPPER.readTree(Files.readAllBytes(credFile));
        assertEquals("sk-ant-test", root.get("anthropicApiKey").asText(),
            "foreign top-level fields must be preserved across update()");
        assertTrue(root.get("mcpOAuth").has("srv#h"));
    }

    @Test
    void delete_removesFile() {
        PlaintextSecureStorage s = new PlaintextSecureStorage(tempDir);
        s.update(SecureStorageData.empty());
        assertTrue(Files.exists(s.filePathForTest()));

        assertTrue(s.delete());
        assertFalse(Files.exists(s.filePathForTest()));
    }

    @Test
    void delete_returnsTrue_whenFileAlreadyMissing() {
        assertTrue(new PlaintextSecureStorage(tempDir).delete());
    }

    @Test
    void update_omitsNullFields_soDiskStaysCompact() throws IOException {
        PlaintextSecureStorage s = new PlaintextSecureStorage(tempDir);
        var entry = new SecureStorageData.McpOAuthEntry(
            "github", null, null, null, "at", null, 0L, null, null);
        s.update(new SecureStorageData(Map.of("k", entry), null, null));

        String content = Files.readString(s.filePathForTest());
        assertFalse(Strings.CS.contains(content, "clientSecret"), "null fields should be omitted");
        assertFalse(Strings.CS.contains(content, "refreshToken"), "null fields should be omitted");
        assertTrue(Strings.CS.contains(content, "\"accessToken\" : \"at\""));
    }

    @Test
    void codec_encode_replacesKnownFields_evenIfInputHadThem() {
        // Prior file had a stale mcpOAuth entry — encode must overwrite, not merge.
        var priorRoot = MAPPER.createObjectNode();
        var mcp = MAPPER.createObjectNode();
        mcp.putObject("stale#hash").put("accessToken", "OLD");
        priorRoot.set("mcpOAuth", mcp);
        priorRoot.put("anthropicApiKey", "sk-keep");

        var newData = new SecureStorageData(
            Map.of("fresh#hash", new SecureStorageData.McpOAuthEntry(
                "fresh", null, null, null, "NEW", null, 0L, null, null)),
            null, null);
        var out = SecureStorageCodec.encode(newData, priorRoot);

        assertNull(out.get("mcpOAuth").get("stale#hash"), "stale entry must be dropped");
        assertEquals("NEW",      out.get("mcpOAuth").get("fresh#hash").get("accessToken").asText());
        assertEquals("sk-keep",  out.get("anthropicApiKey").asText(), "foreign fields survive");
    }

    @Test
    void update_thenRead_roundTripsPluginSecrets() {
        PlaintextSecureStorage s = new PlaintextSecureStorage(tempDir);
        s.update(new SecureStorageData(null, null,
            Map.of("demo@market", Map.of("token", "secret-value")), null));

        SecureStorageData read = s.read().orElseThrow();
        assertEquals("secret-value", read.pluginSecrets().get("demo@market").get("token"));
    }
}
