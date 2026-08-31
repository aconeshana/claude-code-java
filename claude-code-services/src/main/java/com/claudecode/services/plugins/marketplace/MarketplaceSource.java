package com.claudecode.services.plugins.marketplace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

/**
 * Where a marketplace manifest is fetched from — the discriminated union keyed on the {@code
 * source} field in / settings entries.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "source")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MarketplaceSource.Url.class, name = "url"),
    @JsonSubTypes.Type(value = MarketplaceSource.Github.class, name = "github"),
    @JsonSubTypes.Type(value = MarketplaceSource.Git.class, name = "git"),
    @JsonSubTypes.Type(value = MarketplaceSource.Npm.class, name = "npm"),
    @JsonSubTypes.Type(value = MarketplaceSource.File.class, name = "file"),
    @JsonSubTypes.Type(value = MarketplaceSource.Directory.class, name = "directory"),
    @JsonSubTypes.Type(value = MarketplaceSource.HostPattern.class, name = "hostPattern"),
    @JsonSubTypes.Type(value = MarketplaceSource.PathPattern.class, name = "pathPattern"),
})
public sealed interface MarketplaceSource
    permits MarketplaceSource.Url, MarketplaceSource.Github, MarketplaceSource.Git,
            MarketplaceSource.Npm, MarketplaceSource.File, MarketplaceSource.Directory,
            MarketplaceSource.HostPattern, MarketplaceSource.PathPattern {

    /**
     * Whether this source points at a user-controlled local filesystem path (its installLocation is the
     * user's own path, never removed by cache cleanup).
     */
    default boolean isLocal() {
        return this instanceof File || this instanceof Directory;
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    record Url(String url, Map<String, String> headers) implements MarketplaceSource {
        public Url(String url) {
            this(url, null);
        }
    }

    /** GitHub repository in {@code owner/repo} format. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Github(String repo, String ref, String path, List<String> sparsePaths)
        implements MarketplaceSource {
        public Github(String repo) {
            this(repo, null, null, null);
        }

        public Github(String repo, String ref) {
            this(repo, ref, null, null);
        }
    }

    /** Full git repository URL (SSH or HTTPS). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Git(String url, String ref, String path, List<String> sparsePaths)
        implements MarketplaceSource {
        public Git(String url) {
            this(url, null, null, null);
        }

        public Git(String url, String ref) {
            this(url, ref, null, null);
        }
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    record Npm(@JsonProperty("package") String packageName) implements MarketplaceSource {}


    @JsonIgnoreProperties(ignoreUnknown = true)
    record File(String path) implements MarketplaceSource {}

/** Local directory containing. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Directory(String path) implements MarketplaceSource {}

    /** Regex host pattern — allowlist-only entry in {@code strictKnownMarketplaces}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HostPattern(String hostPattern) implements MarketplaceSource {}

    /** Regex path pattern — allowlist-only entry in {@code strictKnownMarketplaces}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PathPattern(String pathPattern) implements MarketplaceSource {}
}
