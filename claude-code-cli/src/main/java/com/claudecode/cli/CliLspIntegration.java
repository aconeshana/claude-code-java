package com.claudecode.cli;

import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.lsp.LspPluginRecommendation;
import com.claudecode.core.lsp.LspRecommendationResponse;
import com.claudecode.lsp.LspBootstrap;
import com.claudecode.lsp.LspServerSettings;
import com.claudecode.services.plugins.LspRecommendationService;
import com.claudecode.services.plugins.marketplace.InstalledPluginsStore;
import com.claudecode.services.plugins.marketplace.MarketplaceManager;
import com.claudecode.services.plugins.marketplace.PluginDirectories;
import com.claudecode.services.plugins.marketplace.PluginScope;
import com.claudecode.services.plugins.marketplace.PluginSettingsStore;
import com.claudecode.services.plugins.runtime.PluginRuntimeSnapshot;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.lsp.LSPTool;
import com.claudecode.ui.lanterna.repl.LanternaReplScreen;
import com.claudecode.ui.render.LspDiagnosticRenderer;
import com.claudecode.core.config.ClaudePaths;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI-owned LSP bootstrap, reload, diagnostics, and recommendation lifecycle.
 */
final class CliLspIntegration {
    private static final Logger log = LoggerFactory.getLogger(CliLspIntegration.class);
    private final LspBootstrap bootstrap;
    private final LspRecommendationService recommendations;
    private final Object applyLock = new Object();
    private final AtomicLong requestedGeneration = new AtomicLong();
    private volatile Runnable pluginRefresh = () -> { };

    private CliLspIntegration(Path cwd) {
        // Publish a stable empty service immediately. Plugin discovery applies
        // its one shared snapshot later and server handshakes stay asynchronous.
        bootstrap = LspBootstrap.wire(cwd, Map.of());
        recommendations = new LspRecommendationService(MarketplaceManager.standard(cwd.toString()),
            new InstalledPluginsStore(PluginDirectories.standard().installedPluginsFile()), ClaudePaths.GLOBAL_JSON);
    }

    static CliLspIntegration wire(Path cwd, ToolRegistry tools, boolean enableTool) {
        CliLspIntegration integration = new CliLspIntegration(cwd);
        if (enableTool) tools.register(new LSPTool(integration.bootstrap.lspService()));
        return integration;
    }

    /** Applies one already-loaded plugin generation; never scans plugin directories. */
    void applySnapshot(Path cwd, PluginRuntimeSnapshot snapshot, QuerySession engine) {
        long generation = requestedGeneration.incrementAndGet();
        Map<String, LspServerSettings> config = configFromSnapshot(snapshot);
        synchronized (applyLock) {
            if (generation != requestedGeneration.get()) return;
            bootstrap.rewire(cwd, config);
            if (engine != null) {
                engine.execution().setFileChangeListener(bootstrap.fileChangeListener());
            }
        }
        log.info("Applied {} LSP server config(s) from plugin snapshot", config.size());
    }

    void setPluginRefresh(Runnable refresh) {
        pluginRefresh = refresh == null ? () -> { } : refresh;
    }

    void attachDiagnostics(QuerySession engine) {
        bootstrap.passiveFeedback().addListener(feedback -> {
            if (!feedback.hasErrors() && !feedback.hasWarnings()) return;
            try {
                engine.conversation().injectSystemReminder(LspDiagnosticRenderer.renderFileDiagnostics(
                    Path.of(URI.create(feedback.fileUri())), feedback.diagnostics()));
            } catch (Exception _) { }
        });
        engine.execution().setFileChangeListener(bootstrap.fileChangeListener());
    }

    void attachRecommendationTrigger(QuerySession engine, LanternaReplScreen screen) {
        if (engine.conversation().getFileHistoryManager() == null) return;
        engine.conversation().getFileHistoryManager().setNewExtensionListener(file ->
            Thread.ofVirtual().name("lsp-rec-trigger").start(() -> onFileTracked(screen, file)));
    }

    private void onFileTracked(LanternaReplScreen screen, Path file) {
        if (recommendations.isDisabled() || recommendations.hasShownThisSession()) return;
        List<LspPluginRecommendation> matches = recommendations.getMatchingLspPlugins(file);
        if (matches.isEmpty()) return;
        recommendations.markShownThisSession();
        LspPluginRecommendation recommendation = matches.getFirst();
        try { screen.showLspRecommendation(recommendation,
            (response, timedOut) -> handleRecommendation(recommendation, response, timedOut)); }
        catch (Exception e) { log.warn("LSP recommendation prompt failed: {}", e.getMessage()); }
    }

    private void handleRecommendation(LspPluginRecommendation rec, LspRecommendationResponse response,
                                      boolean timedOut) {
        try {
            switch (response) {
                case YES -> {
                    Path cwd = Path.of(System.getProperty("user.dir"));
                    MarketplaceManager manager = MarketplaceManager.standard(cwd.toString());
                    var entry = manager.list().get(rec.marketplaceName());
                    if (entry != null) manager.add(entry.source());
                    PluginSettingsStore.standard(cwd.toString()).setEnabledPlugin(rec.pluginId(), true, PluginScope.USER);
                    pluginRefresh.run();
                }
                case NO -> { if (timedOut) recommendations.incrementIgnoredCount(); }
                case NEVER -> recommendations.addToNeverSuggest(rec.pluginId());
                case DISABLE -> recommendations.setDisabled(true);
            }
        } catch (Exception e) { log.warn("LSP recommendation response handling failed: {}", e.getMessage()); }
    }

    private static Map<String, LspServerSettings> configFromSnapshot(
            PluginRuntimeSnapshot snapshot) {
        Map<String, LspServerSettings> configured = new HashMap<>();
        try {
            if (snapshot == null) return Map.of();
            for (Map.Entry<String, JsonNode> entry : snapshot.lspServers().entrySet()) {
                LspServerSettings server = LspServerSettings.fromNode(entry.getValue());
                if (server.extensionToLanguage().isEmpty()) {
                    log.warn("Ignoring LSP server {} without file-extension mappings", entry.getKey());
                    continue;
                }
                for (String language : new LinkedHashSet<>(server.extensionToLanguage().values())) {
                    configured.put(language, server);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to apply plugin LSP servers: {}", e.getMessage());
        }
        return Map.copyOf(configured);
    }
}
