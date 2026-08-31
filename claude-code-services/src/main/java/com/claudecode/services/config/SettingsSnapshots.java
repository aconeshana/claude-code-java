package com.claudecode.services.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.claudecode.permissions.RuleSource;
import com.claudecode.core.serialization.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Detached effective-settings snapshots for SDK and integration consumers.
 */
public final class SettingsSnapshots {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsSnapshots.class);
    private static final Map<String, ObjectNode> EFFECTIVE_CACHE = new ConcurrentHashMap<>();

    private SettingsSnapshots() {}

    public static ObjectNode withSources(String cwd) {
        // SDK get_settings is a fresh observation boundary: it must not retain a parsed tree when
        // an external editor rewrites a file with a timestamp the filesystem cannot distinguish.
        invalidateForReload();
        SettingsSources.refreshFlagSettingsFileOnNextRead();
        ObjectNode snapshot = buildWithSources(cwd);
        EFFECTIVE_CACHE.put(cacheKey(cwd), snapshot.path("effective").deepCopy());
        return snapshot;
    }

    /**
     * Returns a detached effective snapshot without forcing a disk-cache reset. Runtime readers
     * use this path so ordinary hot loops retain the parsed-tree cache; explicit SDK snapshots use
     * {@link #withSources(String)} above instead.
     */
    public static ObjectNode effective(String cwd) {
        ObjectNode cached = EFFECTIVE_CACHE.computeIfAbsent(cacheKey(cwd), _ ->
            buildWithSources(cwd).path("effective").deepCopy());
        return cached.deepCopy();
    }

    private static ObjectNode buildWithSources(String cwd) {
        ObjectNode effective = SettingsSources.pluginSettingsBaseSnapshot();
        ArrayNode sources = JsonUtils.getMapper().createArrayNode();
        Set<Path> seenFilePaths = new HashSet<>();
        for (RuleSource source : SettingsSources.enabledOrder()) {
            boolean mergeIntoEffective = true;
            if (source != RuleSource.POLICY_SETTINGS) {
                Path sourcePath = source == RuleSource.FLAG_SETTINGS
                    ? SettingsSources.flagSettingsPath() : SettingsSources.editablePath(source, cwd);
                if (sourcePath != null) {
                    mergeIntoEffective = seenFilePaths.add(sourcePath.toAbsolutePath().normalize());
                }
            }
            ObjectNode settings = switch (source) {
                case FLAG_SETTINGS -> SettingsSources.flagSettingsSnapshot();
                case POLICY_SETTINGS -> policySnapshot();
                default -> SettingsTreeReader.readAccepted(
                    SettingsSources.editablePath(source, cwd), true);
            };
            if (settings == null || settings.isEmpty()) {

                // An aliased --settings file may still contribute a non-empty
                // inline flag overlay below, but the empty editable alias is
                // not a source entry of its own.
                continue;
            }
            if (mergeIntoEffective) {
                effective = (ObjectNode) SettingsMerger.merge(effective, settings);
            } else if (source == RuleSource.FLAG_SETTINGS) {
                // The aliased file was already merged, but the SDK overlay is a distinct layer.
                ObjectNode inline = SettingsTreeReader.acceptedInline(SettingsSources.inlineSnapshot());
                if (inline != null && !inline.isEmpty()) {
                    effective = (ObjectNode) SettingsMerger.merge(effective, inline);
                }
            }
            ObjectNode sourceNode = sources.addObject();
            sourceNode.put("source", sourceName(source));
            sourceNode.set("settings", settings.deepCopy());
        }
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.set("effective", effective);
        result.set("sources", sources);
        return result;
    }

    static ObjectNode withSources(List<Map.Entry<String, Path>> tiers) {
        ObjectNode effective = JsonUtils.getMapper().createObjectNode();
        ArrayNode sources = JsonUtils.getMapper().createArrayNode();
        for (Map.Entry<String, Path> tier : tiers) {
            ObjectNode settings = SettingsTreeReader.readAccepted(tier.getValue(), false);
            if (settings == null || settings.isEmpty()) continue;
            effective = (ObjectNode) SettingsMerger.merge(effective, settings);
            ObjectNode source = sources.addObject();
            source.put("source", tier.getKey());
            source.set("settings", settings.deepCopy());
        }
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.set("effective", effective);
        result.set("sources", sources);
        return result;
    }

    /** Single selected policy source: platform admin, file/drop-ins, then HKCU. */
    static ObjectNode policySnapshot() {
        ObjectNode admin = MdmSettingsStore.readAdminSettings();
        if (admin != null && !admin.isEmpty()) return admin.deepCopy();
        ObjectNode file = managedFileSettings();
        if (file != null && !file.isEmpty()) return file;
        ObjectNode user = MdmSettingsStore.readUserSettings();
        if (user != null && !user.isEmpty()) return user.deepCopy();
        return file == null ? JsonUtils.getMapper().createObjectNode() : file;
    }

    /** Package-private reset used by writes, source changes, and hot reload. */
    static void invalidateForReload() {
        EFFECTIVE_CACHE.clear();
        SettingsTreeReader.invalidateCache();
        SettingsDiagnostics.invalidateForReload();
    }

    private static String cacheKey(String cwd) {
        if (StringUtils.isBlank(cwd)) return "";
        return Path.of(cwd).toAbsolutePath().normalize().toString();
    }

    static ObjectNode managedFileSettings() {
        ObjectNode settings = loadPolicySettingsSnapshot(policyFiles());
        return settings == null ? JsonUtils.getMapper().createObjectNode() : settings;
    }

    /**
     * Package-private policy-file merge seam. Files are applied in caller-provided order; absent
     * and malformed fragments do not erase an earlier accepted managed layer.
     */
    static ObjectNode loadPolicySettingsSnapshot(List<Path> files) {
        ObjectNode merged = JsonUtils.getMapper().createObjectNode();
        boolean found = false;
        for (Path path : files) {
            if (!Files.isReadable(path)) continue;
            try {
                ObjectNode accepted = SettingsTreeReader.readAccepted(path, false);
                if (accepted != null && !accepted.isEmpty()) {
                    merged = (ObjectNode) SettingsMerger.merge(merged, accepted);
                    found = true;
                }
            } catch (RuntimeException e) {
                LOG.warn("Failed to read managed settings from {}: {}", path, e.getMessage());
            }
        }
        return found ? merged : null;
    }

    static List<Path> policyFiles() {
        Path base = SettingsPaths.policySettingsPath().toAbsolutePath().normalize();
        List<Path> files = new ArrayList<>();
        files.add(base);
        Path dropIns = SettingsPaths.policySettingsDropInDirectory();
        try (var entries = Files.list(dropIns)) {
            entries
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return !Strings.CS.startsWith(name, ".") &&Strings.CS.endsWith( name, ".json")
                        && (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(path));
                })
                .sorted(Comparator.comparing(left -> left.getFileName().toString()))
                .forEach(files::add);
        } catch (IOException | SecurityException e) {
            if (Files.exists(dropIns)) {
                LOG.warn("Failed to list managed settings drop-ins from {}: {}",
                    dropIns, e.getMessage());
            }
        }
        return List.copyOf(files);
    }

    private static String sourceName(RuleSource source) {
        return switch (source) {
            case USER_SETTINGS -> "userSettings";
            case PROJECT_SETTINGS -> "projectSettings";
            case LOCAL_SETTINGS -> "localSettings";
            case FLAG_SETTINGS -> "flagSettings";
            case POLICY_SETTINGS -> "policySettings";
            default -> throw new IllegalArgumentException("Not a settings source: " + source);
        };
    }
}
