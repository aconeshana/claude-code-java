package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Installs, enables/disables, and uninstalls plugins from registered marketplaces.
 */
public final class PluginInstaller {

    private final MarketplaceManager marketplaces;
    private final PluginDirectories directories;
    private final PluginSettingsStore settings;
    private final InstalledPluginsStore installedStore;
    private final GitExecutor git;
    private final String projectPath;

    /**
     * @param projectPath recorded on project/local-scope installations.
     */
    public PluginInstaller(MarketplaceManager marketplaces, GitExecutor git, String projectPath) {
        this.marketplaces = marketplaces;
        this.directories = marketplaces.directories();
        this.settings = marketplaces.settings();
        this.installedStore = marketplaces.installedStore();
        this.git = git;
        this.projectPath = projectPath;
    }

    public record InstallResult(String pluginId, String version, Path installPath) {}
    public record UninstallResult(boolean removed, boolean lastScope) {}

    /** A plugin's installations merged with its enabledPlugins state (null = no settings entry). */
    public record InstalledPluginStatus(
        String pluginId,
        List<InstalledPlugins.InstallationEntry> installations,
        Boolean enabled) {}

    // ── install ───────────────────────────────────────────────────────────────

    public InstallResult install(String pluginName, String marketplaceName, PluginScope scope) {
        String pluginId = pluginName + "@" + marketplaceName;

        MarketplaceManifest manifest = marketplaces.get(marketplaceName);
        MarketplacePluginEntry entry = manifest.findPlugin(pluginName)
            .orElseThrow(() -> new PluginOperationException(
                "Plugin " + pluginId + " not found in marketplace " + marketplaceName));

        Path stagingDir = createStagingDir();
        try {
            String gitSha = materializeSource(entry, marketplaceName, stagingDir);

            Optional<PluginManifest> sourceManifest = readPluginManifest(stagingDir);
            PluginManifest pluginManifest = sourceManifest.orElseGet(entry::toFallbackManifest);
            if (sourceManifest.isEmpty()) {
                persistEffectiveManifest(stagingDir, pluginManifest);
            }
            String version = PluginVersioning.calculate(
                pluginManifest.version(), entry.version(), gitSha);

            Path versionedPath = directories.versionedCachePath(pluginId, version);
            moveIntoVersionedCache(stagingDir, versionedPath);

            String now = Instant.now().toString();
            InstalledPlugins.InstallationEntry installation = new InstalledPlugins.InstallationEntry(
                scope,
                scope == PluginScope.PROJECT || scope == PluginScope.LOCAL ? projectPath : null,
                versionedPath.toString(),
                version,
                now,
                now,
                gitSha);
            installedStore.save(installedStore.load().withInstallation(pluginId, installation));

            settings.setEnabledPlugin(pluginId, true, scope);

            return new InstallResult(pluginId, version, versionedPath);
        } finally {
            FileUtils.deleteRecursively(stagingDir);
        }
    }

    /**
     * Removes only the installation matching scope(+project path).
     */
    public UninstallResult uninstall(String pluginId, PluginScope scope,
                                     boolean deleteDataDirectory) {
        String scopedProjectPath = scope == PluginScope.PROJECT || scope == PluginScope.LOCAL
            ? projectPath : null;
        InstalledPlugins installed = installedStore.load();
        boolean existed = installed.installationsOf(pluginId).stream()
            .anyMatch(entry -> entry.scope() == scope
                && Objects.equals(entry.projectPath(), scopedProjectPath));
        if (!existed) {
            return new UninstallResult(false, false);
        }
        InstalledPlugins updated = installed.withoutInstallation(pluginId, scope, scopedProjectPath);
        installedStore.save(updated);
        settings.removeEnabledPlugin(pluginId, scope);
        boolean lastScope = updated.installationsOf(pluginId).isEmpty();
        if (lastScope && deleteDataDirectory) {
            FileUtils.deleteRecursively(directories.pluginDataDir(pluginId));
        }
        return new UninstallResult(true, lastScope);
    }

    /** Sets {@code enabledPlugins[pluginId] = true} at the given scope. */
    public void enable(String pluginId, PluginScope scope) {
        settings.setEnabledPlugin(pluginId, true, scope);
    }

    /** Sets {@code enabledPlugins[pluginId] = false} at the given scope. */
    public void disable(String pluginId, PluginScope scope) {
        settings.setEnabledPlugin(pluginId, false, scope);
    }

    /** Installed plugins merged with their enabledPlugins state. */
    public List<InstalledPluginStatus> listInstalled() {
        Map<String, Boolean> enabled = settings.enabledPlugins();
        List<InstalledPluginStatus> result = new ArrayList<>();
        installedStore.load().plugins().forEach((pluginId, installations) ->
            result.add(new InstalledPluginStatus(pluginId, installations, enabled.get(pluginId))));
        return List.copyOf(result);
    }

    /**
     * Re-materializes every installed plugin belonging to a refreshed marketplace
     * and moves each installation record to the newly calculated version path.
     * Enablement lives in settings and is intentionally not touched.
     *
     * @return plugin IDs whose recorded version changed
     */
    public List<String> updateMarketplacePlugins(String marketplaceName) {
        InstalledPlugins installed = installedStore.load();
        List<String> updated = new ArrayList<>();
        for (String pluginId : installed.plugins().keySet()) {
            if (!Strings.CS.endsWith(pluginId, "@" + marketplaceName)) continue;
            if (updateInstalledPlugin(pluginId, installed.installationsOf(pluginId)).updated()) {
                updated.add(pluginId);
                installed = installedStore.load();
            }
        }
        return List.copyOf(updated);
    }

    public record UpdateResult(boolean updated, String version) {}

    /** Updates one scope only, preserving every other installation record. */
    public UpdateResult updatePlugin(String pluginId, PluginScope scope) {
        List<InstalledPlugins.InstallationEntry> matches = installedStore.load()
            .installationsOf(pluginId).stream()
            .filter(entry -> entry.scope() == scope)
            .filter(entry -> scope != PluginScope.PROJECT && scope != PluginScope.LOCAL
                || Objects.equals(entry.projectPath(), projectPath))
            .toList();
        if (matches.isEmpty()) {
            throw new PluginOperationException("Plugin \"" + pluginName(pluginId)
                + "\" is not installed at scope " + scope.name().toLowerCase(Locale.ROOT));
        }
        return updateInstalledPlugin(pluginId, matches);
    }

    private UpdateResult updateInstalledPlugin(String pluginId,
                                          List<InstalledPlugins.InstallationEntry> existing) {
        int separator = pluginId.lastIndexOf('@');
        if (separator <= 0 || separator == pluginId.length() - 1 || existing.isEmpty()) {
            return new UpdateResult(false, null);
        }
        String pluginName = pluginId.substring(0, separator);
        String marketplaceName = pluginId.substring(separator + 1);
        MarketplacePluginEntry entry = marketplaces.get(marketplaceName).findPlugin(pluginName)
            .orElseThrow(() -> new PluginOperationException(
                "Plugin " + pluginId + " not found in marketplace " + marketplaceName));
        Path stagingDir = createStagingDir();
        try {
            String gitSha = materializeSource(entry, marketplaceName, stagingDir);
            Optional<PluginManifest> sourceManifest = readPluginManifest(stagingDir);
            PluginManifest manifest = sourceManifest.orElseGet(entry::toFallbackManifest);
            if (sourceManifest.isEmpty()) {
                persistEffectiveManifest(stagingDir, manifest);
            }
            String version = PluginVersioning.calculate(manifest.version(), entry.version(), gitSha);
            if (existing.stream().allMatch(installation -> version.equals(installation.version()))) {
                return new UpdateResult(false, version);
            }
            Path versionedPath = directories.versionedCachePath(pluginId, version);
            moveIntoVersionedCache(stagingDir, versionedPath);
            String now = Instant.now().toString();
            InstalledPlugins next = installedStore.load();
            for (InstalledPlugins.InstallationEntry installation : existing) {
                next = next.withInstallation(pluginId, new InstalledPlugins.InstallationEntry(
                    installation.scope(), installation.projectPath(), versionedPath.toString(), version,
                    installation.installedAt(), now, gitSha));
            }
            installedStore.save(next);
            return new UpdateResult(true, version);
        } finally {
            FileUtils.deleteRecursively(stagingDir);
        }
    }

    private static String pluginName(String pluginId) {
        int separator = pluginId.lastIndexOf('@');
        return separator > 0 ? pluginId.substring(0, separator) : pluginId;
    }

    // ── source materialization ────────────────────────────────────────────────

    /**
     * Copies/clones the plugin source into {@code stagingDir}. Returns the full
     * git commit SHA when one is discoverable, else null.
     */
    private String materializeSource(MarketplacePluginEntry entry, String marketplaceName,
                                     Path stagingDir) {
        return switch (entry.source()) {
            case PluginSource.RelativePath relative -> {
                KnownMarketplaces.Entry known = marketplaces.list().get(marketplaceName);
                if (known == null) {
                    throw new PluginOperationException(
                        "Marketplace " + marketplaceName + " not found");
                }
                Path marketplaceRoot = Path.of(known.installLocation());
                Path sourceDir = validatePathWithinBase(marketplaceRoot, relative.path());
                if (!Files.isDirectory(sourceDir)) {
                    throw new PluginOperationException(
                        "Plugin source directory not found: " + sourceDir);
                }
                copyDir(sourceDir, stagingDir);
                // The marketplace clone (not the copy) carries .git — resolve the
                // SHA from the source dir, best-effort.
                yield headShaOf(sourceDir);
            }
            case PluginSource.GitRepo repo -> cloneInto(repo.url(), repo.ref(), stagingDir);
            case PluginSource.GithubRepo repo ->
                cloneInto("https://github.com/" + repo.repo() + ".git", repo.ref(), stagingDir);
            // TODO(stage-2): npm / pip / git-subdir plugin sources.
            case PluginSource.GitSubdir _ -> throw new PluginOperationException(
                "git-subdir plugin sources are not yet supported");
            case PluginSource.Npm _ -> throw new PluginOperationException(
                "NPM plugin sources are not yet supported");
            case PluginSource.Pip _ -> throw new PluginOperationException(
                "Python package plugins are not yet supported");
        };
    }

    private String cloneInto(String gitUrl, String ref, Path stagingDir) {
        FileUtils.deleteRecursively(stagingDir);
        List<String> args = new ArrayList<>(List.of(
            "-c", "core.sshCommand=ssh -o BatchMode=yes -o StrictHostKeyChecking=yes",
            "clone", "--depth", "1"));
        if (ref != null) {
            args.add("--branch");
            args.add(ref);
        }
        args.add(gitUrl);
        args.add(stagingDir.toString());
        GitExecutor.GitResult result = git.run(null, args);
        if (!result.ok()) {
            throw new PluginOperationException("Failed to clone plugin repository: "
                + MarketplaceManager.enhanceCloneError(result, gitUrl));
        }
        return headShaOf(stagingDir);
    }

    private String headShaOf(Path dir) {
        GitExecutor.GitResult result = git.run(dir, List.of("rev-parse", "HEAD"));
        return result.ok() && !StringUtils.isBlank(result.stdout()) ? result.stdout().trim() : null;
    }

    /**
     * Rejects relative sources that escape the marketplace root.
     */
    static Path validatePathWithinBase(Path basePath, String relativePath) {
        Path base = basePath.toAbsolutePath().normalize();
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.equals(base) && !resolved.startsWith(base)) {
            throw new PluginOperationException("Path traversal detected: \"" + relativePath
                + "\" would escape the base directory");
        }
        return resolved;
    }

    // ── manifest / filesystem helpers ─────────────────────────────────────────

    /**
     * Reads (or legacy root ).
     */
    private static Optional<PluginManifest> readPluginManifest(Path pluginDir) {
        Path nested = pluginDir.resolve(".claude-plugin").resolve("plugin.json");
        Path legacy = pluginDir.resolve("plugin.json");
        Path manifestPath = Files.isRegularFile(nested) ? nested
            : Files.isRegularFile(legacy) ? legacy : null;
        if (manifestPath == null) {
            return Optional.empty();
        }
        PluginManifest manifest;
        try {
            manifest = JsonUtils.getMapper().readValue(manifestPath.toFile(), PluginManifest.class);
        } catch (IOException e) {
            throw new PluginOperationException("Plugin has a corrupt manifest file at "
                + manifestPath + ". JSON parse error: " + e.getMessage(), e);
        }
        if (StringUtils.isEmpty(manifest.name())) {
            throw new PluginOperationException("Plugin has an invalid manifest file at "
                + manifestPath + ". Validation errors: name: Plugin name cannot be empty");
        }
        if (Strings.CS.contains(manifest.name(), " ")) {
            throw new PluginOperationException("Plugin has an invalid manifest file at "
                + manifestPath + ". Validation errors: name: Plugin name cannot contain spaces. "
                + "Use kebab-case (e.g., \"my-plugin\")");
        }
        return Optional.of(manifest);
    }

    /**
     * Retains the effective marketplace-entry manifest for runtime-only cache loads.
     */
    private static void persistEffectiveManifest(Path pluginDir, PluginManifest manifest) {
        Path manifestPath = pluginDir.resolve(".claude-plugin").resolve("plugin.json");
        try {
            Files.createDirectories(manifestPath.getParent());
            JsonUtils.getMapper().writerWithDefaultPrettyPrinter()
                .writeValue(manifestPath.toFile(), manifest);
        } catch (IOException e) {
            throw new PluginOperationException(
                "Failed to persist effective plugin manifest at " + manifestPath
                    + ": " + e.getMessage(), e);
        }
    }

    private Path createStagingDir() {
        try {
            return FileUtils.createTempDir(directories.pluginCacheDir(), "temp_install_");
        } catch (IOException e) {
            throw new PluginOperationException(
                "Failed to create plugin staging directory: " + e.getMessage(), e);
        }
    }

    private static void moveIntoVersionedCache(Path stagingDir, Path versionedPath) {
        try {
            Files.createDirectories(versionedPath.getParent());
            FileUtils.deleteRecursively(versionedPath);
            Files.move(stagingDir, versionedPath);
        } catch (IOException e) {
            throw new PluginOperationException(
                "Failed to move plugin into versioned cache: " + e.getMessage(), e);
        }
    }

    static void copyDir(Path src, Path dest) {
        try {
            FileUtils.copyDirectory(src, dest);
        } catch (IOException e) {
            throw new PluginOperationException(
                "Failed to copy plugin source: " + e.getMessage(), e);
        }
    }
}
