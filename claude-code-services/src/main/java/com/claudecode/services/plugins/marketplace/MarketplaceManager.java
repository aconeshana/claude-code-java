package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


import static com.claudecode.core.io.FileUtils.deleteRecursively;

import com.claudecode.http.HttpCalls;
import com.claudecode.services.http.ServiceHttpClient;
import com.claudecode.core.serialization.JsonUtils;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Manages known marketplaces: add (clone/download/reference), list, get, update, persisting state
 * to and enforcing enterprise policy before any network or filesystem side effect.
 */
public final class MarketplaceManager {

    private static final Logger LOG = LoggerFactory.getLogger(MarketplaceManager.class);
    private static final Duration URL_FETCH_TIMEOUT = Duration.ofSeconds(10);

    private final PluginDirectories directories;
    private final GitExecutor git;
    private final OkHttpClient http;
    private final PluginSettingsStore settings;
    private final MarketplacePolicy policy;
    private final KnownMarketplacesStore knownStore;
    private final InstalledPluginsStore installedStore;

    public MarketplaceManager(Path pluginsRoot, GitExecutor git, OkHttpClient http,
                              PluginSettingsStore settings) {
        this.directories = new PluginDirectories(pluginsRoot);
        this.git = git;
        this.http = http;
        this.settings = settings;
        this.policy = new MarketplacePolicy(settings);
        this.knownStore = new KnownMarketplacesStore(directories.knownMarketplacesFile());
        this.installedStore = new InstalledPluginsStore(directories.installedPluginsFile());
    }

    /** Production wiring: {@code ~/.claude/plugins}, real git subprocesses, shared OkHttp. */
    public static MarketplaceManager standard(String cwd) {
        return new MarketplaceManager(
            PluginDirectories.standard().root(),
            new ProcessGitExecutor(),
            ServiceHttpClient.marketplace(),
            PluginSettingsStore.standard(cwd));
    }

    public PluginDirectories directories() {
        return directories;
    }

    PluginSettingsStore settings() {
        return settings;
    }

    InstalledPluginsStore installedStore() {
        return installedStore;
    }

    /** Result of {@link #add}: resolved name, and whether the source already existed. */
    public record AddResult(String name, boolean alreadyMaterialized, MarketplaceSource resolvedSource) {}

    public AddResult add(MarketplaceSource source) {
        return add(source, _ -> {});
    }

    /**
     * Adds a marketplace: policy check, source-idempotency check, fetch/copy +
     * validate, then persist to  under the name
     * declared in the fetched manifest.
     */
    public AddResult add(MarketplaceSource source, Consumer<String> onProgress) {
        MarketplaceSource resolvedSource = resolveLocalPath(source);

        checkPolicy(resolvedSource);

        // Source-idempotency: if this exact source already exists, skip the fetch.
        KnownMarketplaces existing = knownStore.load();
        for (Map.Entry<String, KnownMarketplaces.Entry> entry : existing.entries().entrySet()) {
            if (resolvedSource.equals(entry.getValue().source())) {
                return new AddResult(entry.getKey(), true, resolvedSource);
            }
        }

        CachedMarketplace cached = loadAndCache(resolvedSource, onProgress);

        String officialError = MarketplaceNames.validateOfficialNameSource(
            cached.manifest().name(), resolvedSource);
        if (officialError != null) {
            throw new PluginOperationException(officialError);
        }

        // Name collision with a different source: overwrite (intent wins), cleaning
        // the old cache only when it genuinely differs and lives inside the cache dir.
        KnownMarketplaces config = knownStore.load();
        KnownMarketplaces.Entry oldEntry = config.get(cached.manifest().name());
        if (oldEntry != null && !oldEntry.source().isLocal()) {
            cleanupReplacedCache(oldEntry.installLocation(), cached.cachePath());
        }

        config = config.with(cached.manifest().name(), new KnownMarketplaces.Entry(
            resolvedSource, cached.cachePath().toString(), Instant.now().toString(), null));
        knownStore.save(config);

        return new AddResult(cached.manifest().name(), false, resolvedSource);
    }

    /** All registered marketplaces (name → entry). */
    public Map<String, KnownMarketplaces.Entry> list() {
        return knownStore.loadSafe().entries();
    }

    /**
     * Reads a marketplace manifest from its local cache (no network). Throws
     * when the marketplace is unknown or its cache is unreadable.
     */
    public MarketplaceManifest get(String name) {
        KnownMarketplaces config = knownStore.load();
        KnownMarketplaces.Entry entry = config.get(name);
        if (entry == null) {
            throw new PluginOperationException("Marketplace '" + name
                + "' not found in configuration. Available marketplaces: "
                + String.join(", ", config.entries().keySet()));
        }
        return readCachedMarketplace(Path.of(entry.installLocation()));
    }


    public void remove(String name) {
        KnownMarketplaces config = knownStore.load();
        if (!config.contains(name)) {
            throw new PluginOperationException("Marketplace '" + name + "' not found");
        }
        knownStore.save(config.without(name));

        deleteRecursively(directories.marketplacesCacheDir().resolve(name));
        deleteRecursively(directories.marketplacesCacheDir().resolve(name + ".json"));

        settings.removeMarketplaceReferences(name);

        InstalledPlugins installed = installedStore.load();
        List<String> removedIds = installed.plugins().keySet().stream()
            .filter(id -> Strings.CS.endsWith(id, "@" + name))
            .toList();
        if (!removedIds.isEmpty()) {
            installedStore.save(installed.withoutMarketplace(name));

            removedIds.forEach(id -> deleteRecursively(directories.pluginDataDir(id)));
        }
    }

    public void update(String name) {
        update(name, _ -> {});
    }

    /**
     * Refreshes a single marketplace in place: git pull (re-clone fallback) for
     * git/github sources, re-download for URL sources, cache re-validation for
     * local sources. Updates {@code lastUpdated} on success.
     */
    public void update(String name, Consumer<String> onProgress) {
        KnownMarketplaces config = knownStore.load();
        KnownMarketplaces.Entry entry = config.get(name);
        if (entry == null) {
            throw new PluginOperationException("Marketplace '" + name
                + "' not found. Available marketplaces: "
                + String.join(", ", config.entries().keySet()));
        }

        try {
            Path installLocation = Path.of(entry.installLocation());
            MarketplaceSource source = entry.source();

            // A corrupted installLocation (cross-platform path writes, manual
            // edits) could point at the user's project — refuse rather than
            // running git ops / rm there. (gh-32793, gh-32661)
            if (!source.isLocal()) {
                Path cacheDir = directories.marketplacesCacheDir().toAbsolutePath().normalize();
                Path resolved = installLocation.toAbsolutePath().normalize();
                if (!resolved.equals(cacheDir) && !resolved.startsWith(cacheDir)) {
                    throw new PluginOperationException("Marketplace '" + name
                        + "' has a corrupted installLocation (" + entry.installLocation()
                        + ") — expected a path inside " + cacheDir
                        + ". This can happen after cross-platform path writes or manual edits "
                        + "to known_marketplaces.json. "
                        + "Run: claude plugin marketplace remove \"" + name + "\" and re-add it.");
                }
            }

            switch (source) {
                case MarketplaceSource.Github github -> {
                    String sshUrl = "git@github.com:" + github.repo() + ".git";
                    String httpsUrl = "https://github.com/" + github.repo() + ".git";
                    try {
                        cacheFromGit(sshUrl, installLocation, github.ref(), onProgress);
                    } catch (PluginOperationException _) {
                        onProgress.accept("SSH update failed, retrying with HTTPS: " + httpsUrl);
                        cacheFromGit(httpsUrl, installLocation, github.ref(), onProgress);
                    }
                    verifyManifestStillPresent(name, installLocation, github.repo());
                }
                case MarketplaceSource.Git gitSource -> {
                    cacheFromGit(gitSource.url(), installLocation, gitSource.ref(), onProgress);
                    verifyManifestStillPresent(name, installLocation, gitSource.url());
                }
                case MarketplaceSource.Url url ->
                    cacheFromUrl(url.url(), installLocation, url.headers(), onProgress);
                case MarketplaceSource.File _ -> {
                    onProgress.accept("Validating local marketplace");
                    readCachedMarketplace(installLocation);
                }
                case MarketplaceSource.Directory _ -> {
                    onProgress.accept("Validating local marketplace");
                    readCachedMarketplace(installLocation);
                }
                default -> throw new PluginOperationException(
                    "Unsupported marketplace source type for refresh");
            }

            knownStore.save(config.with(name, new KnownMarketplaces.Entry(
                entry.source(), entry.installLocation(), Instant.now().toString(),
                entry.autoUpdate())));
        } catch (PluginOperationException e) {
            throw new PluginOperationException(
                "Failed to refresh marketplace '" + name + "': " + e.getMessage(), e);
        }
    }


    public void setAutoUpdate(String name, boolean autoUpdate) {
        KnownMarketplaces config = knownStore.load();
        KnownMarketplaces.Entry entry = config.get(name);
        if (entry == null) {
            throw new PluginOperationException("Marketplace '" + name
                + "' not found. Available marketplaces: "
                + String.join(", ", config.entries().keySet()));
        }
        if (entry.autoUpdate() != null && entry.autoUpdate() == autoUpdate) {
            return;
        }
        knownStore.save(config.with(name, new KnownMarketplaces.Entry(
            entry.source(), entry.installLocation(), entry.lastUpdated(), autoUpdate)));
    }

    // ── policy ────────────────────────────────────────────────────────────────

    private void checkPolicy(MarketplaceSource source) {
        if (policy.isSourceAllowedByPolicy(source)) {
            return;
        }
        if (policy.isSourceInBlocklist(source)) {
            throw new PluginOperationException("Marketplace source '"
                + MarketplacePolicy.formatSourceForDisplay(source)
                + "' is blocked by enterprise policy.");
        }
        List<MarketplaceSource> allowlist = policy.allowedSources();
        String sourceHost = MarketplacePolicy.extractHostFromSource(source);

        StringBuilder message = new StringBuilder("Marketplace source '")
            .append(MarketplacePolicy.formatSourceForDisplay(source)).append("'");
        if (sourceHost != null) {
            message.append(" (").append(sourceHost).append(")");
        }
        message.append(" is blocked by enterprise policy.");
        if (!allowlist.isEmpty()) {
            message.append(" Allowed sources: ").append(String.join(", ",
                allowlist.stream().map(MarketplacePolicy::formatSourceForDisplay).toList()));
        } else {
            message.append(" No external marketplaces are allowed.");
        }
        if (source instanceof MarketplaceSource.Github github
                && !policy.hostPatternsFromAllowlist().isEmpty()) {
            message.append("\n\nTip: The shorthand \"").append(github.repo())
                .append("\" assumes github.com. ")
                .append("For internal GitHub Enterprise, use the full URL:\n")
                .append("  git@your-github-host.com:").append(github.repo()).append(".git");
        }
        throw new PluginOperationException(message.toString());
    }

    // ── load and cache ────────────────────────────────────────────────────────

    private record CachedMarketplace(MarketplaceManifest manifest, Path cachePath) {}

    private CachedMarketplace loadAndCache(MarketplaceSource source, Consumer<String> onProgress) {
        Path cacheDir = directories.marketplacesCacheDir();
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new PluginOperationException(
                "Failed to create marketplace cache directory: " + e.getMessage(), e);
        }

        String tempName = tempCacheNameFor(source);
        Path temporaryCachePath;
        Path marketplacePath;

        try {
            switch (source) {
                case MarketplaceSource.Url url -> {
                    temporaryCachePath = cacheDir.resolve(tempName + ".json");
                    cacheFromUrl(url.url(), temporaryCachePath, url.headers(), onProgress);
                    marketplacePath = temporaryCachePath;
                }
                case MarketplaceSource.Github github -> {

                    String sshUrl = "git@github.com:" + github.repo() + ".git";
                    String httpsUrl = "https://github.com/" + github.repo() + ".git";
                    temporaryCachePath = cacheDir.resolve(tempName);
                    try {
                        onProgress.accept("Cloning via SSH: " + sshUrl);
                        cacheFromGit(sshUrl, temporaryCachePath, github.ref(), onProgress);
                    } catch (PluginOperationException _) {
                        onProgress.accept("SSH clone failed, retrying with HTTPS: " + httpsUrl);
                        deleteRecursively(temporaryCachePath);
                        cacheFromGit(httpsUrl, temporaryCachePath, github.ref(), onProgress);
                    }
                    marketplacePath = temporaryCachePath.resolve(
                        github.path() != null ? github.path() : ".claude-plugin/marketplace.json");
                }
                case MarketplaceSource.Git gitSource -> {
                    temporaryCachePath = cacheDir.resolve(tempName);
                    cacheFromGit(gitSource.url(), temporaryCachePath, gitSource.ref(), onProgress);
                    marketplacePath = temporaryCachePath.resolve(
                        gitSource.path() != null ? gitSource.path() : ".claude-plugin/marketplace.json");
                }
                case MarketplaceSource.Npm _ ->
                    throw new PluginOperationException("NPM marketplace sources not yet implemented");
                case MarketplaceSource.File file -> {
                    Path absolute = Path.of(file.path()).toAbsolutePath().normalize();
                    marketplacePath = absolute;
                    temporaryCachePath = absolute.getParent() != null
                        && absolute.getParent().getParent() != null
                        ? absolute.getParent().getParent() : absolute.getParent();
                }
                case MarketplaceSource.Directory dir -> {
                    Path absolute = Path.of(dir.path()).toAbsolutePath().normalize();
                    marketplacePath = absolute.resolve(".claude-plugin").resolve("marketplace.json");
                    temporaryCachePath = absolute;
                }
                default -> throw new PluginOperationException("Unsupported marketplace source type");
            }

            MarketplaceManifest manifest;
            try {
                manifest = parseMarketplaceFile(marketplacePath);
            } catch (PluginOperationException e) {
                if (!Files.exists(marketplacePath)) {
                    throw new PluginOperationException(
                        "Marketplace file not found at " + marketplacePath);
                }
                throw new PluginOperationException("Failed to parse marketplace file at "
                    + marketplacePath + ": " + e.getMessage(), e);
            }

            // Rename cache to the marketplace's declared name. Defense-in-depth:
            // even though the name validation rejects separators/"..", verify the
            // computed path stays strictly inside the cache dir before any rm.
            Path finalCachePath = cacheDir.resolve(manifest.name());
            Path resolvedFinal = finalCachePath.toAbsolutePath().normalize();
            Path resolvedCacheDir = cacheDir.toAbsolutePath().normalize();
            if (!resolvedFinal.startsWith(resolvedCacheDir) || resolvedFinal.equals(resolvedCacheDir)) {
                throw new PluginOperationException("Marketplace name '" + manifest.name()
                    + "' resolves to a path outside the cache directory");
            }
            // URL sources cache as flat <name>.json files, not directories.
            Path target = source instanceof MarketplaceSource.Url
                ? cacheDir.resolve(manifest.name() + ".json")
                : finalCachePath;
            if (!temporaryCachePath.equals(target) && !source.isLocal()) {
                try {
                    onProgress.accept("Cleaning up old marketplace cache…");
                    deleteRecursively(target);
                    Files.move(temporaryCachePath, target);
                    temporaryCachePath = target;
                } catch (IOException e) {
                    throw new PluginOperationException(
                        "Failed to finalize marketplace cache. Please manually delete the directory at "
                            + target + " if it exists and try again.\n\nTechnical details: "
                            + e.getMessage(), e);
                }
            }

            return new CachedMarketplace(manifest, temporaryCachePath);
        } catch (PluginOperationException e) {
            // Clean up temp files on error (never a user-owned local path).
            if (!source.isLocal()) {
                Path tempDir = cacheDir.resolve(tempName);
                Path tempJson = cacheDir.resolve(tempName + ".json");
                deleteRecursively(tempDir);
                deleteRecursively(tempJson);
            }
            throw e;
        }
    }


    static String tempCacheNameFor(MarketplaceSource source) {
        return switch (source) {
            case MarketplaceSource.Github github -> github.repo().replace("/", "-");
            case MarketplaceSource.Npm npm ->
                npm.packageName().replace("@", "").replace("/", "-");
            case MarketplaceSource.File file -> {
                String base = Path.of(file.path()).getFileName().toString();
                yield base.replace(".json", "");
            }
            case MarketplaceSource.Directory dir -> Path.of(dir.path()).getFileName().toString();
            default -> "temp_" + System.currentTimeMillis();
        };
    }

    // ── git operations ────────────────────────────────────────────────────────

    /**
     * Pull-first (when a repo exists at cachePath), rm + fresh {@code --depth 1} clone otherwise or on
     * pull failure.
     */
    void cacheFromGit(String gitUrl, Path cachePath, String ref, Consumer<String> onProgress) {
        onProgress.accept("Refreshing marketplace cache…");
        if (Files.isDirectory(cachePath.resolve(".git")) && gitPull(cachePath, ref).ok()) {
            return;
        }
        deleteRecursively(cachePath);

        onProgress.accept("Cloning repository: " + redactUrlCredentials(gitUrl)
            + (ref != null ? " (ref: " + ref + ")" : ""));
        GitExecutor.GitResult result = gitClone(gitUrl, cachePath, ref);
        if (!result.ok()) {
            deleteRecursively(cachePath);
            // Scrub credentials before the message can reach logs / bug reports.
            String enhanced = enhanceCloneError(result, gitUrl)
                .replace(gitUrl, redactUrlCredentials(gitUrl));
            throw new PluginOperationException(
                "Failed to clone marketplace repository: " + enhanced);
        }
        onProgress.accept("Clone complete, validating marketplace…");
    }

    /**
     * Redacts userinfo in http(s) URLs (e.g.
     */
    static String redactUrlCredentials(String urlString) {
        try {
            URI uri = URI.create(urlString);
            String scheme = uri.getScheme();
            if ((Strings.CS.equals("http", scheme) || Strings.CS.equals("https", scheme))
                    && uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
                String userInfo = uri.getUserInfo();
                String redacted = Strings.CS.contains(userInfo, ":")
                    ? (Strings.CS.startsWith(userInfo, ":") ? ":***" : "***:***")
                    : "***";
                return urlString.replaceFirst(Pattern.quote(userInfo) + "@",
                    Matcher.quoteReplacement(redacted) + "@");
            }
        } catch (IllegalArgumentException _) {
            // Not a valid URL — safe as-is.
        }
        return urlString;
    }

    /** {@code git clone --depth 1 [--branch ref] url target} with no-prompt SSH config. */
    GitExecutor.GitResult gitClone(String gitUrl, Path targetPath, String ref) {
        List<String> args = new ArrayList<>(List.of(
            "-c", "core.sshCommand=ssh -o BatchMode=yes -o StrictHostKeyChecking=yes",
            "clone", "--depth", "1"));
        if (ref != null) {
            args.add("--branch");
            args.add(ref);
        }
        args.add(gitUrl);
        args.add(targetPath.toString());
        return git.run(null, args);
    }

    /** With ref: fetch + checkout + pull that ref; without: {@code pull origin HEAD}. */
    GitExecutor.GitResult gitPull(Path cwd, String ref) {
        if (ref != null) {
            GitExecutor.GitResult fetch = git.run(cwd, List.of("fetch", "origin", ref));
            if (!fetch.ok()) {
                return fetch;
            }
            GitExecutor.GitResult checkout = git.run(cwd, List.of("checkout", ref));
            if (!checkout.ok()) {
                return checkout;
            }
            return git.run(cwd, List.of("pull", "origin", ref));
        }
        return git.run(cwd, List.of("pull", "origin", "HEAD"));
    }


    static String enhanceCloneError(GitExecutor.GitResult result, String gitUrl) {
        String stderr = result.stderr() == null ? "" : result.stderr();
        if (Strings.CS.contains(stderr, "timed out") && result.code() == -1) {
            return "Git clone timed out. The repository may be too large for the current timeout. "
                + "Set CLAUDE_CODE_PLUGIN_GIT_TIMEOUT_MS to increase it (e.g., 300000 for 5 minutes)."
                + "\n\nOriginal error: " + stderr;
        }
        if (Strings.CS.contains(stderr, "REMOTE HOST IDENTIFICATION HAS CHANGED")) {
            return "SSH host key has changed (server key rotation or possible MITM). "
                + "Remove the stale known_hosts entry:\n  ssh-keygen -R " + sshHostOf(gitUrl)
                + "\nThen connect once manually to verify and accept the new key."
                + "\n\nOriginal error: " + stderr;
        }
        if (Strings.CS.contains(stderr, "Host key verification failed")) {
            return "SSH host key is not in your known_hosts file. To add it, connect once manually "
                + "(this will show the fingerprint for you to verify):\n  ssh -T git@" + sshHostOf(gitUrl)
                + "\n\nOr use an HTTPS URL instead (recommended for public repos)."
                + "\n\nOriginal error: " + stderr;
        }
        if (Strings.CS.contains(stderr, "Permission denied (publickey)")
                || Strings.CS.contains(stderr, "Could not read from remote repository")) {
            return "SSH authentication failed. Please ensure your SSH keys are configured for GitHub, "
                + "or use an HTTPS URL instead.\n\nOriginal error: " + stderr;
        }
        if (Strings.CS.contains(stderr, "Authentication failed") || Strings.CS.contains(stderr, "could not read Username")
                || Strings.CS.contains(stderr, "terminal prompts disabled")
                || Strings.CS.contains(stderr, "403") || Strings.CS.contains(stderr, "401")) {
            return "HTTPS authentication failed. Please ensure your credential helper is configured "
                + "(e.g., gh auth login).\n\nOriginal error: " + stderr;
        }
        if (Strings.CS.contains(stderr, "timed out") || Strings.CS.contains(stderr, "timeout")
                || Strings.CS.contains(stderr, "Could not resolve host")) {
            return "Network error or timeout while cloning repository. Please check your internet "
                + "connection and try again.\n\nOriginal error: " + stderr;
        }
        if (stderr.isEmpty()) {
            return "git clone exited with code " + result.code() + " (no stderr output).";
        }
        return stderr;
    }

    private static String sshHostOf(String gitUrl) {
        var matcher = Pattern.compile("^[^@]+@([^:]+):").matcher(gitUrl);
        return matcher.find() ? matcher.group(1) : "<host>";
    }

    // ── URL download ──────────────────────────────────────────────────────────


    void cacheFromUrl(String url, Path cachePath, Map<String, String> headers,
                      Consumer<String> onProgress) {
        onProgress.accept("Downloading marketplace from " + url);
        Request.Builder request = new Request.Builder().url(url).get();
        if (headers != null) {
            headers.forEach(request::header);
        }
        // User-Agent set last to prevent override (consistency with WebFetch).
        request.header("User-Agent", "Claude-Code-Plugin-Manager");

        String body;
        int statusCode;
        try (Response response = HttpCalls.execute(http, request.build(), URL_FETCH_TIMEOUT)) {
            statusCode = response.code();
            body = response.body().string();
        } catch (InterruptedIOException e) {
            throw new PluginOperationException("Request timed out while downloading marketplace from "
                + url + ". The server may be slow or unreachable.\n\nTechnical details: " + e.getMessage(), e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PluginOperationException("Could not connect to " + url
                + ". Please check your internet connection and verify the URL is correct."
                + "\n\nTechnical details: " + e.getMessage(), e);
        }
        if (statusCode < 200 || statusCode >= 300) {
            throw new PluginOperationException("HTTP " + statusCode
                + " error while downloading marketplace from " + url
                + ". The marketplace file may not exist at this URL.");
        }

        onProgress.accept("Validating marketplace data");
        MarketplaceManifest manifest;
        try {
            manifest = JsonUtils.getMapper().readValue(body, MarketplaceManifest.class);
        } catch (IOException e) {
            throw new PluginOperationException(
                "Invalid marketplace schema from URL: " + e.getMessage(), e);
        }
        String nameError = validateManifest(manifest);
        if (nameError != null) {
            throw new PluginOperationException("Invalid marketplace schema from URL: " + nameError);
        }

        onProgress.accept("Saving marketplace to cache");
        try {
            Files.createDirectories(cachePath.getParent());
            JsonUtils.writeJson(cachePath, manifest, true);
        } catch (IOException e) {
            throw new PluginOperationException(
                "Failed to save marketplace to cache: " + e.getMessage(), e);
        }
    }

    // ── manifest reading / validation ─────────────────────────────────────────

    /**
     * Reads a cached marketplace: nested first (git clones / directories), then the location itself as
     * a flat file (URL/file caches).
     */
    public MarketplaceManifest readCachedMarketplace(Path installLocation) {
        Path nested = installLocation.resolve(".claude-plugin").resolve("marketplace.json");
        if (Files.isRegularFile(nested)) {
            return parseMarketplaceFile(nested);
        }
        return parseMarketplaceFile(installLocation);
    }

    private MarketplaceManifest parseMarketplaceFile(Path file) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new PluginOperationException(
                "Marketplace file not found at " + file, e);
        }
        MarketplaceManifest manifest;
        try {
            manifest = JsonUtils.getMapper().readValue(content, MarketplaceManifest.class);
        } catch (IOException e) {
            throw new PluginOperationException(
                "Invalid JSON in " + file + ": " + e.getMessage(), e);
        }
        String error = validateManifest(manifest);
        if (error != null) {
            throw new PluginOperationException("Invalid schema: " + file + " " + error);
        }
        return manifest;
    }





    static String validateManifest(MarketplaceManifest manifest) {
        String nameError = MarketplaceNames.validate(manifest.name());
        if (nameError != null) {
            return "name: " + nameError;
        }
        if (manifest.owner() == null || manifest.owner().name() == null
                || manifest.owner().name().isEmpty()) {
            return "owner.name: Author name cannot be empty";
        }
        if (manifest.plugins() == null) {
            return "plugins: Required";
        }
        for (int i = 0; i < manifest.plugins().size(); i++) {
            MarketplacePluginEntry entry = manifest.plugins().get(i);
            if (StringUtils.isEmpty(entry.name())) {
                return "plugins[" + i + "].name: Plugin name cannot be empty";
            }
            if (Strings.CS.contains(entry.name(), " ")) {
                return "plugins[" + i + "].name: Plugin name cannot contain spaces. "
                    + "Use kebab-case (e.g., \"my-plugin\")";
            }
            if (entry.source() == null) {
                return "plugins[" + i + "].source: Required";
            }
        }
        return null;
    }

    private void verifyManifestStillPresent(String name, Path installLocation, String sourceDisplay) {
        try {
            readCachedMarketplace(installLocation);
        } catch (PluginOperationException _) {
            throw new PluginOperationException(
                "The marketplace.json file is no longer present in this repository.\n\n"
                    + "This marketplace may have been deprecated or moved to a new location.\n"
                    + "Source: " + sourceDisplay + "\n\n"
                    + "You can remove this marketplace with: claude plugin marketplace remove \""
                    + name + "\"");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static MarketplaceSource resolveLocalPath(MarketplaceSource source) {

        if (source instanceof MarketplaceSource.File(String path) && !Path.of(path).isAbsolute()) {
            return new MarketplaceSource.File(
                Path.of(path).toAbsolutePath().normalize().toString());
        }
        if (source instanceof MarketplaceSource.Directory(String path) && !Path.of(path).isAbsolute()) {
            return new MarketplaceSource.Directory(
                Path.of(path).toAbsolutePath().normalize().toString());
        }
        return source;
    }

    private void cleanupReplacedCache(String oldLocation, Path newCachePath) {
        Path cacheDir = directories.marketplacesCacheDir().toAbsolutePath().normalize();
        Path resolvedOld = Path.of(oldLocation).toAbsolutePath().normalize();
        Path resolvedNew = newCachePath.toAbsolutePath().normalize();
        if (resolvedOld.equals(resolvedNew)) {
            return; // Same dir — already overwritten in place, nothing to clean.
        }
        if (resolvedOld.startsWith(cacheDir) && !resolvedOld.equals(cacheDir)) {
            deleteRecursively(resolvedOld);
        } else {
            LOG.warn("Skipping cleanup of old installLocation ({}) — outside {}. "
                + "Leaving it alone and overwriting the config entry.", oldLocation, cacheDir);
        }
    }
}
