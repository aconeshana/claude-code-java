package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/**
 * Where a plugin is fetched from — either a relative path within the marketplace repository (JSON
 * string) or an external source object (JSON object keyed on {@code source}).
 */
@JsonSerialize(using = PluginSource.Serializer.class)
@JsonDeserialize(using = PluginSource.Deserializer.class)
public sealed interface PluginSource
    permits PluginSource.RelativePath, PluginSource.GitRepo, PluginSource.GithubRepo,
            PluginSource.GitSubdir, PluginSource.Npm, PluginSource.Pip {


    default boolean isLocal() {
        return this instanceof RelativePath rp && Strings.CS.startsWith(rp.path(), "./");
    }

    /** Path to the plugin root, relative to the marketplace root. */
    record RelativePath(String path) implements PluginSource {}


    record GitRepo(String url, String ref, String sha) implements PluginSource {}

    /** GitHub repository in {@code owner/repo} format — discriminator {@code "github"}. */
    record GithubRepo(String repo, String ref, String sha) implements PluginSource {}

    /** Plugin in a subdirectory of a monorepo — discriminator {@code "git-subdir"}. */
    record GitSubdir(String url, String path, String ref, String sha) implements PluginSource {}


    record Npm(String packageName, String version, String registry) implements PluginSource {}


    record Pip(String packageName, String version, String registry) implements PluginSource {}

    final class Serializer extends JsonSerializer<PluginSource> {
        @Override
        public void serialize(PluginSource value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            switch (value) {
                case RelativePath rp -> gen.writeString(rp.path());
                case GitRepo g -> {
                    gen.writeStartObject();
                    gen.writeStringField("source", "url");
                    gen.writeStringField("url", g.url());
                    writeOptional(gen, "ref", g.ref());
                    writeOptional(gen, "sha", g.sha());
                    gen.writeEndObject();
                }
                case GithubRepo g -> {
                    gen.writeStartObject();
                    gen.writeStringField("source", "github");
                    gen.writeStringField("repo", g.repo());
                    writeOptional(gen, "ref", g.ref());
                    writeOptional(gen, "sha", g.sha());
                    gen.writeEndObject();
                }
                case GitSubdir g -> {
                    gen.writeStartObject();
                    gen.writeStringField("source", "git-subdir");
                    gen.writeStringField("url", g.url());
                    gen.writeStringField("path", g.path());
                    writeOptional(gen, "ref", g.ref());
                    writeOptional(gen, "sha", g.sha());
                    gen.writeEndObject();
                }
                case Npm n -> {
                    gen.writeStartObject();
                    gen.writeStringField("source", "npm");
                    gen.writeStringField("package", n.packageName());
                    writeOptional(gen, "version", n.version());
                    writeOptional(gen, "registry", n.registry());
                    gen.writeEndObject();
                }
                case Pip p -> {
                    gen.writeStartObject();
                    gen.writeStringField("source", "pip");
                    gen.writeStringField("package", p.packageName());
                    writeOptional(gen, "version", p.version());
                    writeOptional(gen, "registry", p.registry());
                    gen.writeEndObject();
                }
            }
        }

        private static void writeOptional(JsonGenerator gen, String field, String value)
                throws IOException {
            if (value != null) {
                gen.writeStringField(field, value);
            }
        }
    }

    final class Deserializer extends StdDeserializer<PluginSource> {
        Deserializer() {
            super(PluginSource.class);
        }

        @Override
        public PluginSource deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node.isTextual()) {
                return new RelativePath(node.asText());
            }
            if (!node.isObject()) {
                throw new IOException("Plugin source must be a string or an object, got: " + node.getNodeType());
            }
            String discriminator = node.path("source").asText("");
            return switch (discriminator) {
                case "url" -> new GitRepo(text(node, "url"), text(node, "ref"), text(node, "sha"));
                case "github" -> new GithubRepo(text(node, "repo"), text(node, "ref"), text(node, "sha"));
                case "git-subdir" -> new GitSubdir(
                    text(node, "url"), text(node, "path"), text(node, "ref"), text(node, "sha"));
                case "npm" -> new Npm(text(node, "package"), text(node, "version"), text(node, "registry"));
                case "pip" -> new Pip(text(node, "package"), text(node, "version"), text(node, "registry"));
                default -> throw new IOException("Unsupported plugin source type: " + discriminator);
            };
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value != null && value.isTextual() ? value.asText() : null;
        }
    }
}
