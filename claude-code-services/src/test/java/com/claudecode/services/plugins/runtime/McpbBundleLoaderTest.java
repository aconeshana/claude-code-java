package com.claudecode.services.plugins.runtime;

import org.apache.commons.lang3.Strings;
import com.claudecode.services.plugins.marketplace.UserConfigOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class McpbBundleLoaderTest {

    @TempDir
    Path tmp;

    @Test
    void localDxtExtractsSafelyAndExpandsDefaultsUserConfigAndSystemVariables() throws Exception {
        Path plugin = Files.createDirectories(tmp.resolve("plugin"));
        Files.write(plugin.resolve("server.dxt"), bundle(Map.of(
            "manifest.json", """
                {
                  "manifest_version":"0.4","name":"sample","version":"1.0.0",
                  "description":"Sample server","author":{"name":"Tester"},
                  "server":{"type":"binary","entry_point":"bin/server",
                    "mcp_config":{"command":"${__dirname}/bin/server",
                      "args":["--home","${HOME}","${user_config.tags}"],
                      "env":{"MODE":"${user_config.mode}"}}},
                  "user_config":{
                    "mode":{"type":"string","title":"Mode","description":"Run mode","default":"safe"},
                    "tags":{"type":"string","title":"Tags","description":"Tags","required":true,"multiple":true}
                  }
                }
                """,
            "bin/server", "binary")));

        McpbBundleLoader.Result needsConfig = new McpbBundleLoader().load(
            "server.dxt", plugin, _ -> Map.of());
        assertTrue(needsConfig.needsConfig());
        assertEquals("sample", needsConfig.serverName());
        assertTrue(needsConfig.validationErrors().stream().anyMatch(error -> Strings.CS.contains(error, "Tags")));
        assertTrue(Files.isRegularFile(needsConfig.extractedPath().resolve("bin/server")));

        McpbBundleLoader.Result loaded = new McpbBundleLoader().load(
            "server.dxt", plugin, _ -> Map.of("tags", List.of("one", "two")));
        assertFalse(loaded.needsConfig());
        assertEquals(needsConfig.extractedPath(), loaded.extractedPath(), "fresh cache is reused");
        assertEquals(loaded.extractedPath().resolve("bin/server").toString(),
            loaded.mcpConfig().path("command").asText());
        assertEquals("--home", loaded.mcpConfig().path("args").get(0).asText());
        assertEquals(System.getProperty("user.home"),
            loaded.mcpConfig().path("args").get(1).asText());
        assertEquals("one", loaded.mcpConfig().path("args").get(2).asText());
        assertEquals("two", loaded.mcpConfig().path("args").get(3).asText());
        assertEquals("safe", loaded.mcpConfig().path("env").path("MODE").asText());
        UserConfigOption tags = loaded.configSchema().get("tags");
        assertEquals(Boolean.TRUE, tags.required());
        assertEquals(Boolean.TRUE, tags.multiple());
    }

    @Test
    void archiveTraversalIsRejectedWithoutWritingOutsideCache() throws Exception {
        Path plugin = Files.createDirectories(tmp.resolve("plugin"));
        Files.write(plugin.resolve("bad.mcpb"), bundle(Map.of(
            "manifest.json", """
                {"manifest_version":"0.4","name":"bad","version":"1","description":"bad",
                 "author":{"name":"Tester"},"server":{"type":"binary","entry_point":"server",
                 "mcp_config":{"command":"${__dirname}/server"}}}
                """,
            "../escaped", "owned")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new McpbBundleLoader().load("bad.mcpb", plugin, _ -> Map.of()));
        assertTrue(Strings.CS.contains(error.getMessage(), "Unsafe file path"));
        assertFalse(Files.exists(plugin.resolve("escaped")));
    }

    private static byte[] bundle(Map<String, String> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
