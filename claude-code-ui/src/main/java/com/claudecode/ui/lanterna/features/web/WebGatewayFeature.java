package com.claudecode.ui.lanterna.features.web;

import com.claudecode.runtime.gateway.GatewaySupervisorPort;
import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.ui.lanterna.components.LogoPanel;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The web gateway (third session endpoint) as seen from the REPL: the startup warmup that feeds
 * the welcome block's quick-entry row, and the explicit {@code /web} command that opens the
 * token-embedded URL in the system browser.
 *
 * <p>Both paths share one in-flight start through {@link GatewaySupervisorPort#startAsync()} so
 * a {@code /web} typed during warmup never binds a second listener. The URL is the only place
 * the token is displayed; it never goes to logs.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>Java-side extension (no 197 counterpart): the in-process HTTP+SSE gateway entry and its
 *       welcome-block quick-entry row.</li>
 * </ul>
 */
public final class WebGatewayFeature implements ReplCommandUiBridge.WebGateway {

    private static final Logger log = LoggerFactory.getLogger(WebGatewayFeature.class);

    private final GatewaySupervisorPort gateway;
    private final PluginMarketplacePort browser;
    private final ReplTranscriptSink transcript;
    private final Consumer<String> welcomeWebEntry;

    /**
     * @param gateway         null when the gateway is unavailable in this session
     * @param browser         opens external URLs; null disables browser launch
     * @param welcomeWebEntry receives the gateway URL (or null after a failed warmup) so the
     *                        welcome block can show or clear its quick-entry row
     */
    public WebGatewayFeature(GatewaySupervisorPort gateway, PluginMarketplacePort browser,
                             ReplTranscriptSink transcript, Consumer<String> welcomeWebEntry) {
        this.gateway = gateway;
        this.browser = browser;
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.welcomeWebEntry = Objects.requireNonNull(welcomeWebEntry, "welcomeWebEntry");
    }

    /**
     * Eagerly starts the gateway in the background at REPL startup so the welcome block can
     * surface its quick-entry row almost immediately. A warmup failure is silent — it must not
     * disturb the first frame; {@code /web} remains the explicit retry path.
     */
    public void warmUp() {
        if (gateway == null) return;
        gateway.startAsync().whenComplete((started, failure) -> {
            if (started != null) {
                log.info("[LANTERNA] Web gateway warmed up: {}", LogoPanel.webOrigin(started.url()));
            } else if (failure != null) {
                log.debug("Web gateway warmup failed: {}", failure.toString());
            }
            welcomeWebEntry.accept(started != null ? started.url() : null);
        });
    }

    /**
     * {@code /web}: waits for the gateway to be running — joining an in-flight start rather than
     * starting a second binding — then opens the URL in the system browser and surfaces it as a
     * transcript line (the copyable fallback when no browser can be spawned).
     */
    @Override
    public void startWebGateway() {
        if (gateway == null) {
            transcript.system("Web gateway is not available in this session.");
            return;
        }
        Thread.ofVirtual().name("web-gateway-start").start(() -> {
            GatewaySupervisorPort.Started started;
            try {
                started = gateway.start();
            } catch (RuntimeException failure) {
                log.warn("[LANTERNA] Web gateway failed to start", failure);
                transcript.system("Web gateway failed to start: " + failure.getMessage());
                return;
            }
            if (browser == null || !browser.openExternalUrl(started.url())) {
                transcript.system("Could not open a browser — open the URL manually.");
            }
            welcomeWebEntry.accept(started.url());
            transcript.system("Web gateway: " + started.url());
        });
    }
}
