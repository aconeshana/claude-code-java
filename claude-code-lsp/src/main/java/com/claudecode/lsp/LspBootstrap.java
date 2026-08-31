package com.claudecode.lsp;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.engine.FileChangeListener;
import com.claudecode.core.io.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assembles the LSP subsystem at startup: reads {@code lspServers} from enabled plugins, spawns
 * whichever servers are configured, and wires diagnostic push-notifications through to {@link
 * PassiveFeedback}.
 */
public final class LspBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(LspBootstrap.class);

    private final LspService lspService;
    private final LspDiagnosticRegistry registry;
    private final PassiveFeedback passiveFeedback;
    private final Map<String, String> extensionToLanguageId;
    private final Set<String> openedFiles = ConcurrentHashMap.newKeySet();

    private LspBootstrap(LspService lspService, LspDiagnosticRegistry registry, PassiveFeedback passiveFeedback,
            Map<String, String> extensionToLanguageId) {
        this.lspService = lspService;
        this.registry = registry;
        this.passiveFeedback = passiveFeedback;
        this.extensionToLanguageId = extensionToLanguageId;
    }

    /**
     * Package-private overload for tests that need to inject settings directly.
     */
    public static LspBootstrap wire(Path workspaceRoot, Map<String, LspServerSettings> configured) {
        LspDiagnosticRegistry registry = new LspDiagnosticRegistry();
        PassiveFeedback passiveFeedback = new PassiveFeedback(registry);

        Map<String, String> extensionToLanguageId = new ConcurrentHashMap<>();
        LspService lspService = new LspService(extensionToLanguageId);

        spawnServers(lspService, registry, workspaceRoot, configured, extensionToLanguageId);
        return new LspBootstrap(lspService, registry, passiveFeedback, extensionToLanguageId);
    }

    /**
     * Tear down every running server and rebuild from {@code configured}, reusing the existing {@link
     * LspDiagnosticRegistry}/{@link PassiveFeedback} and the same routing map — so listeners and the
     * diagnostic dedup state stay valid.
     */
    public void rewire(Path workspaceRoot, Map<String, LspServerSettings> configured) {
        lspService.shutdownAll();
        registry.clearAll();
        openedFiles.clear();
        spawnServers(lspService, registry, workspaceRoot, configured, extensionToLanguageId);
    }

    /**
     * Populate {@code extensionToLanguageId} (cleared first) from the configured servers' {@code
     * extensionToLanguage} maps, and spawn one subprocess per settings instance, registering it under
     * each of its language ids.
     */
    private static void spawnServers(LspService lspService, LspDiagnosticRegistry registry,
            Path workspaceRoot, Map<String, LspServerSettings> configured,
            Map<String, String> extensionToLanguageId) {
        extensionToLanguageId.clear();

        // Group by settings instance so a plugin server that maps to several
        // language ids spawns exactly one subprocess, then register that one
        // instance under each of its language ids.
        Map<LspServerSettings, List<String>> settingsToLanguages = new LinkedHashMap<>();
        for (Map.Entry<String, LspServerSettings> entry : configured.entrySet()) {
            extensionToLanguageId.putAll(entry.getValue().extensionToLanguage());
            settingsToLanguages.computeIfAbsent(entry.getValue(), _ -> new ArrayList<>())
                .add(entry.getKey());
        }

        for (Map.Entry<LspServerSettings, List<String>> entry : settingsToLanguages.entrySet()) {
            registerAsync(lspService, registry, workspaceRoot, entry.getValue(), entry.getKey());
        }
    }

    /** Spawn + initialize one server off the startup thread. Disabled entries never spawn a thread. */
    private static void registerAsync(LspService lspService, LspDiagnosticRegistry registry,
            Path workspaceRoot, List<String> languages, LspServerSettings settings) {
        if (!settings.enabled()) return;
        Thread.ofVirtual().name("lsp-init-" + languages.getFirst()).start(
            () -> registerIfPossible(lspService, registry, workspaceRoot, languages, settings));
    }

    private static void registerIfPossible(LspService lspService, LspDiagnosticRegistry registry,
            Path workspaceRoot, List<String> languages, LspServerSettings settings) {
        if (!settings.enabled()) return;
        try {
            LspServerInstance server = resolveServer(settings, registry);
            if (server == null) return;
            // Register before initialize so the server is discoverable (and a
            // navigation request can wait for it via LspService.awaitReady)
            // while the potentially slow handshake/index is still in flight.
            for (String languageId : languages) {
                lspService.registerServer(server, languageId);
            }
            server.initialize(workspaceRoot);
        } catch (Exception ex) {
            // Not installed / bad command / server crashed on startup — this is
            // the common case for users who haven't configured that language,
            // so debug (not warn/error) to avoid log noise.
            for (String languageId : languages) {
                lspService.unregisterServer(languageId);
            }
            LOG.debug("LSP server '{}' unavailable: {}", languages, ex.getMessage());
        }
    }

    private static LspServerInstance resolveServer(LspServerSettings settings,
            LspDiagnosticRegistry registry) {
        if (StringUtils.isBlank(settings.command())) {
            if (settings.extensionToLanguage().containsValue(JdtLsPreset.LANGUAGE_ID)) {
                return JdtLsPreset.create(null, registry).orElse(null);
            }
            LOG.debug("No command configured for LSP server (langs={}), skipping",
                settings.extensionToLanguage().values());
            return null;
        }
        // Derive the instance's default language id from its extension mapping
        // (falls back to "plaintext" only when no mapping is declared at all).
        String languageId = settings.extensionToLanguage().isEmpty()
            ? "plaintext"
            : settings.extensionToLanguage().values().iterator().next();
        return new ProcessLspServerInstance(languageId, settings, registry);
    }

    public LspService lspService() {
        return lspService;
    }

    public LspDiagnosticRegistry registry() {
        return registry;
    }

    public PassiveFeedback passiveFeedback() {
        return passiveFeedback;
    }

    /**
     * Builds a {@link FileChangeListener} that routes Write/Edit tool results to
     * whichever registered server handles the file's extension — {@code didOpen}
     * the first time a file is seen, {@code didChange} on subsequent edits, and
     * {@code didSave} on each disk write (in the CLI model a write is a save;
     * GAP-2).
     * Files with no matching {@code extensionToLanguage} entry (and no server
     * registered for the resolved language) are silently ignored.
     */
    public FileChangeListener fileChangeListener() {
        return (filePath, _) -> {
// Editing a file invalidates previously delivered diagnostics, so drop the dedup state
// for it.
            registry.clearDeliveredFingerprints(filePath.toUri().toString());

            String languageId = extensionToLanguageId.get(PathUtils.extensionOf(filePath));
            if (languageId == null) return;
            lspService.getServer(languageId).ifPresent(server -> {
                try {
                    String content = Files.readString(filePath);
                    String key = filePath.toAbsolutePath().toString();
                    if (openedFiles.add(key)) {
                        server.didOpen(filePath, content);
                    } else {
                        server.didChange(filePath, content);
                    }
                    server.didSave(filePath);
                } catch (IOException e) {
                    LOG.debug("Failed to read {} for LSP notification: {}", filePath, e.getMessage());
                }
            });
        };
    }
}
