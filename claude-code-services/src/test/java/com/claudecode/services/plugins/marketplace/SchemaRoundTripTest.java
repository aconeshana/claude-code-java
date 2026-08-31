package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SchemaRoundTripTest {

    private final ObjectMapper mapper = JsonUtils.getMapper();



    @Test
    void parsesMarketplaceJsonWithStringAndObjectSources() throws Exception {
        String json = """
            {
              "name": "test-marketplace",
              "owner": {"name": "Tester", "email": "t@example.com"},
              "metadata": {"description": "A test marketplace", "version": "1.0.0"},
              "plugins": [
                {"name": "local-plugin", "source": "./plugins/local", "description": "local"},
                {"name": "gh-plugin", "source": {"source": "github", "repo": "owner/repo", "ref": "main"}},
                {"name": "strict-off", "source": "./plugins/off", "strict": false, "version": "2.0.0"}
              ]
            }
            """;
        MarketplaceManifest manifest = mapper.readValue(json, MarketplaceManifest.class);

        assertEquals("test-marketplace", manifest.name());
        assertEquals("Tester", manifest.owner().name());
        assertEquals("A test marketplace", manifest.metadata().description());
        assertEquals(3, manifest.plugins().size());

        MarketplacePluginEntry local = manifest.findPlugin("local-plugin").orElseThrow();
        PluginSource.RelativePath relative =
            assertInstanceOf(PluginSource.RelativePath.class, local.source());
        assertEquals("./plugins/local", relative.path());
        assertTrue(local.source().isLocal());
        assertTrue(local.strictOrDefault(), "strict defaults to true");

        MarketplacePluginEntry gh = manifest.findPlugin("gh-plugin").orElseThrow();
        PluginSource.GithubRepo github =
            assertInstanceOf(PluginSource.GithubRepo.class, gh.source());
        assertEquals("owner/repo", github.repo());
        assertEquals("main", github.ref());
        assertFalse(gh.source().isLocal());

        assertFalse(manifest.findPlugin("strict-off").orElseThrow().strictOrDefault());
    }

    @Test
    void unknownFieldsAreTolerated() throws Exception {
        String json = """
            {
              "name": "m", "owner": {"name": "o", "customField": 1},
              "plugins": [{"name": "p", "source": "./p", "futureField": {"a": 1}}],
              "somethingNew": true
            }
            """;
        MarketplaceManifest manifest = mapper.readValue(json, MarketplaceManifest.class);
        assertEquals("m", manifest.name());
        assertEquals(1, manifest.plugins().size());
    }

    @Test
    void marketplaceSourceRoundTripsForEveryType() throws Exception {
        List<MarketplaceSource> sources = List.of(
            new MarketplaceSource.Github("owner/repo", "main", null, List.of("plugins")),
            new MarketplaceSource.Git("git@host:owner/repo.git", "v1", null, null),
            new MarketplaceSource.Url("https://example.com/m.json"),
            new MarketplaceSource.Npm("my-pkg"),
            new MarketplaceSource.File("/tmp/m.json"),
            new MarketplaceSource.Directory("/tmp/mkt"),
            new MarketplaceSource.HostPattern("^github\\.corp\\.com$"),
            new MarketplaceSource.PathPattern("^/opt/approved/"));
        for (MarketplaceSource source : sources) {
            String json = mapper.writeValueAsString(source);
            MarketplaceSource back = mapper.readValue(json, MarketplaceSource.class);
            assertEquals(source, back, "round trip failed for " + json);
        }
    }

    @Test
    void marketplaceSourceSerializesDiscriminatorField() throws Exception {
        String json = mapper.writeValueAsString(new MarketplaceSource.Github("a/b"));
        assertTrue(Strings.CS.contains(json, "\"source\":\"github\""), json);
        assertTrue(Strings.CS.contains(json, "\"repo\":\"a/b\""), json);
    }

    @Test
    void pluginSourceRoundTripsStringAndObjectForms() throws Exception {
        List<PluginSource> sources = List.of(
            new PluginSource.RelativePath("./plugins/x"),
            new PluginSource.GitRepo("https://example.com/r.git", "main", null),
            new PluginSource.GithubRepo("owner/repo", null, "a".repeat(40)),
            new PluginSource.GitSubdir("owner/mono", "tools/plugin", "main", null),
            new PluginSource.Npm("pkg", "^1.0.0", null),
            new PluginSource.Pip("pypkg", null, null));
        for (PluginSource source : sources) {
            String json = mapper.writeValueAsString(source);
            PluginSource back = mapper.readValue(json, PluginSource.class);
            assertEquals(source, back, "round trip failed for " + json);
        }

        assertEquals("\"./plugins/x\"",
            mapper.writeValueAsString(new PluginSource.RelativePath("./plugins/x")));
        assertTrue(Strings.CS.contains(mapper.writeValueAsString(new PluginSource.GitRepo("u", null, null)), "\"source\":\"url\""));
        assertTrue(Strings.CS.contains(mapper.writeValueAsString(new PluginSource.GitSubdir("u", "p", null, null)), "\"source\":\"git-subdir\""));
    }



    @Test
    void commandsAsSingleStringPath() throws Exception {
        PluginManifest manifest = mapper.readValue(
            "{\"name\":\"p\",\"commands\":\"./extra/cmd.md\"}", PluginManifest.class);
        assertEquals(List.of("./extra/cmd.md"), manifest.commandPaths());
    }

    @Test
    void commandsAsArrayOfPaths() throws Exception {
        PluginManifest manifest = mapper.readValue(
            "{\"name\":\"p\",\"commands\":[\"./a.md\",\"./b.md\"]}", PluginManifest.class);
        assertEquals(List.of("./a.md", "./b.md"), manifest.commandPaths());
    }

    @Test
    void commandsAsObjectMappingExtractsSourcePaths() throws Exception {
        String json = """
            {"name":"p","commands":{
              "about": {"source": "./README.md", "description": "About"},
              "inline": {"content": "# inline command"}
            }}
            """;
        PluginManifest manifest = mapper.readValue(json, PluginManifest.class);
        assertEquals(List.of("./README.md"), manifest.commandPaths());
    }

    @Test
    void hooksUnionYieldsOnlyPathStrings() throws Exception {
        PluginManifest single = mapper.readValue(
            "{\"name\":\"p\",\"hooks\":\"./hooks/extra.json\"}", PluginManifest.class);
        assertEquals(List.of("./hooks/extra.json"), single.hookPaths());

        PluginManifest mixed = mapper.readValue(
            "{\"name\":\"p\",\"hooks\":[\"./h1.json\",{\"hooks\":{}}]}", PluginManifest.class);
        assertEquals(List.of("./h1.json"), mixed.hookPaths());

        PluginManifest inline = mapper.readValue(
            "{\"name\":\"p\",\"hooks\":{\"PreToolUse\":[]}}", PluginManifest.class);
        assertEquals(List.of(), inline.hookPaths());
    }

    @Test
    void dependencyRefsNormalizeAllThreeForms() throws Exception {
        String json = """
            {"name":"p","dependencies":[
              "plain-dep",
              "dep@marketplace",
              "dep@mkt@^1.2",
              {"name": "obj-dep", "marketplace": "mkt2", "version": "9.9.9"},
              {"name": "bare-obj"}
            ]}
            """;
        PluginManifest manifest = mapper.readValue(json, PluginManifest.class);
        assertEquals(
            List.of("plain-dep", "dep@marketplace", "dep@mkt", "obj-dep@mkt2", "bare-obj"),
            manifest.dependencyRefs());
    }

    @Test
    void userConfigParsesIntoTypedOptions() throws Exception {
        String json = """
            {"name":"p","userConfig":{
              "API_KEY": {"type":"string","title":"API Key","description":"Your key",
                          "required":true,"sensitive":true},
              "MAX_ITEMS": {"type":"number","title":"Max","description":"cap",
                            "default":10,"min":1,"max":100}
            }}
            """;
        PluginManifest manifest = mapper.readValue(json, PluginManifest.class);
        UserConfigOption apiKey = manifest.userConfig().get("API_KEY");
        assertNotNull(apiKey);
        assertEquals("string", apiKey.type());
        assertEquals("API Key", apiKey.title());
        assertTrue(apiKey.required());
        assertTrue(apiKey.sensitive());

        UserConfigOption max = manifest.userConfig().get("MAX_ITEMS");
        assertEquals(10, max.defaultValue().asInt());
        assertEquals(1.0, max.min());
        assertEquals(100.0, max.max());
        assertNull(max.sensitive());
    }
}
