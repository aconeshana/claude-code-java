package com.claudecode.services.plugins;

import java.util.Locale;

import com.claudecode.core.lsp.LspPluginRecommendation;
import com.claudecode.services.config.GlobalConfigStore;
import com.claudecode.services.plugins.marketplace.InstalledPluginsStore;
import com.claudecode.services.plugins.marketplace.MarketplaceManager;
import com.claudecode.services.plugins.marketplace.MarketplaceManifest;
import com.claudecode.services.plugins.marketplace.MarketplaceNames;
import com.claudecode.services.plugins.marketplace.MarketplacePluginEntry;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


public final class LspRecommendationService {

    private static final int MAX_IGNORED_COUNT = 5;
    private static final String KEY_DISABLED = "lspRecommendationDisabled";
    private static final String KEY_NEVER = "lspRecommendationNeverPlugins";
    private static final String KEY_IGNORED = "lspRecommendationIgnoredCount";

    private final MarketplaceManager marketplaceManager;
    private final InstalledPluginsStore installedStore;
    private final Path globalConfigPath;
    private volatile boolean shownThisSession = false;

    public LspRecommendationService(
            MarketplaceManager marketplaceManager,
            InstalledPluginsStore installedStore,
            Path globalConfigPath) {
        this.marketplaceManager = marketplaceManager;
        this.installedStore = installedStore;
        this.globalConfigPath = globalConfigPath;
    }

    /** True once a recommendation has been shown this session. */
    public boolean hasShownThisSession() {
        return shownThisSession;
    }

    public void markShownThisSession() {
        shownThisSession = true;
    }

    /** Recommendations are off when the user opted out or ignored too many. */
    public boolean isDisabled() {
        return GlobalConfigStore.getBoolean(globalConfigPath, KEY_DISABLED, false)
            || GlobalConfigStore.getInt(globalConfigPath, KEY_IGNORED, 0) >= MAX_IGNORED_COUNT;
    }

    public void setDisabled(boolean disabled) {
        GlobalConfigStore.set(globalConfigPath, KEY_DISABLED, disabled);
    }

    public void addToNeverSuggest(String pluginId) {
        List<String> current = readNeverList();
        if (current.contains(pluginId)) {
            return;
        }
        List<String> next = new ArrayList<>(current);
        next.add(pluginId);
        GlobalConfigStore.set(globalConfigPath, KEY_NEVER, next);
    }

    public void incrementIgnoredCount() {
        int next = GlobalConfigStore.getInt(globalConfigPath, KEY_IGNORED, 0) + 1;
        GlobalConfigStore.set(globalConfigPath, KEY_IGNORED, next);
    }

    public void resetIgnoredCount() {
        if (GlobalConfigStore.getInt(globalConfigPath, KEY_IGNORED, 0) != 0) {
            GlobalConfigStore.set(globalConfigPath, KEY_IGNORED, 0);
        }
    }

    /**
     * Find LSP plugin recommendations for {@code filePath}.
     */
    public List<LspPluginRecommendation> getMatchingLspPlugins(Path filePath) {
        if (isDisabled()) {
            return List.of();
        }
        String ext = extensionOf(filePath);
        if (ext == null) {
            return List.of();
        }
        return matchAgainst(loadCatalog(), ext);
    }

    /** Pure, MarketplaceManager-free core, exercised directly by tests. */
    List<LspPluginRecommendation> matchAgainst(
            Map<String, List<MarketplacePluginEntry>> catalog, String ext) {
        Set<String> never = new LinkedHashSet<>(readNeverList());
        List<LspPluginRecommendation> matches = new ArrayList<>();
        for (Map.Entry<String, List<MarketplacePluginEntry>> market : catalog.entrySet()) {
            String marketplaceName = market.getKey();
            boolean isOfficial = MarketplaceNames.ALLOWED_OFFICIAL_MARKETPLACE_NAMES
                .contains(marketplaceName.toLowerCase(Locale.ROOT));
            for (MarketplacePluginEntry entry : market.getValue()) {
                String pluginId = entry.name() + "@" + marketplaceName;
                if (never.contains(pluginId) || isInstalled(pluginId)) {
                    continue;
                }
                LspInfo info = extractLspInfo(entry.lspServers());
                if (info == null || !info.extensions().contains(ext)) {
                    continue;
                }
                if (!BinaryCheck.isBinaryInstalled(info.command())) {
                    continue;
                }
                matches.add(new LspPluginRecommendation(
                    pluginId, entry.name(), entry.description(),
                    List.copyOf(info.extensions()), info.command(), isOfficial, marketplaceName));
            }
        }
        matches.sort((a, b) -> Boolean.compare(b.isOfficial(), a.isOfficial()));
        return matches;
    }

    private Map<String, List<MarketplacePluginEntry>> loadCatalog() {
        Map<String, List<MarketplacePluginEntry>> catalog = new LinkedHashMap<>();
        for (String marketplaceName : marketplaceManager.list().keySet()) {
            MarketplaceManifest manifest;
            try {
                manifest = marketplaceManager.get(marketplaceName);
            } catch (RuntimeException _) {
                continue;
            }
            if (manifest == null || manifest.plugins() == null) {
                continue;
            }
            catalog.put(marketplaceName, manifest.plugins());
        }
        return catalog;
    }

    private boolean isInstalled(String pluginId) {
        return !installedStore.load().installationsOf(pluginId).isEmpty();
    }

    private List<String> readNeverList() {
        JsonNode node = GlobalConfigStore.getNode(globalConfigPath, KEY_NEVER);
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode e : node) {
                if (e.isTextual()) {
                    list.add(e.asText());
                }
            }
        }
        return list;
    }

    private static String extensionOf(Path filePath) {
        String fileName = filePath.getFileName() == null ? "" : filePath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** Extracted LSP metadata: covered extensions plus the launch command. */
    private record LspInfo(Set<String> extensions, String command) {}


    private static LspInfo extractLspInfo(JsonNode lspServers) {
        if (lspServers == null || lspServers.isNull()) {
            return null;
        }
        if (lspServers.isTextual()) {
            return null;
        }
        if (lspServers.isArray()) {
            for (JsonNode item : lspServers) {
                if (item.isTextual()) {
                    continue;
                }
                LspInfo info = extractFromServerConfigRecord(item);
                if (info != null) {
                    return info;
                }
            }
            return null;
        }
        return extractFromServerConfigRecord(lspServers);
    }

    private static LspInfo extractFromServerConfigRecord(JsonNode serverConfigs) {
        Set<String> extensions = new LinkedHashSet<>();
        String command = null;
        if (!serverConfigs.isObject()) {
            return null;
        }
        for (JsonNode config : serverConfigs) {
            if (!config.isObject()) {
                continue;
            }
            if (command == null && config.has("command") && config.get("command").isTextual()) {
                command = config.get("command").asText();
            }
            JsonNode extMapping = config.get("extensionToLanguage");
            if (extMapping != null && extMapping.isObject()) {
                extMapping.fieldNames().forEachRemaining(ext -> extensions.add(ext.toLowerCase(Locale.ROOT)));
            }
        }
        if (command == null || extensions.isEmpty()) {
            return null;
        }
        return new LspInfo(extensions, command);
    }
}
