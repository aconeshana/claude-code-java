package com.claudecode.ui.lanterna.plugin;

import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.ui.lanterna.input.TextInputs;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.util.ArrayList;
import java.util.List;

import static com.claudecode.ui.lanterna.components.StyledText.blank;
import static com.claudecode.ui.lanterna.components.StyledText.boldLine;
import static com.claudecode.ui.lanterna.components.StyledText.line;
import com.claudecode.ui.lanterna.components.StyledText;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Add-Marketplace input view hosted by {@link PluginMarketplacesTab}: a single text field validated
 * and materialized by {@link PluginMarketplacePort} on a background thread.
 */
final class PluginMarketplaceAddView {

    static final String INVALID_FORMAT_ERROR =
        "Invalid marketplace source format. Try: owner/repo, https://..., or ./path";
    static final String EMPTY_INPUT_ERROR = "Please enter a marketplace source";

    private final PluginPanelServices services;
    private final PluginPanelHost host;

    private final Runnable onBackToList;

    private final StringBuilder input = new StringBuilder();
    private String error;
    private boolean loading;
    private String progress = "";
    private boolean cliMode;

    PluginMarketplaceAddView(PluginPanelServices services, PluginPanelHost host,
                             Runnable onBackToList) {
        this.services = services;
        this.host = host;
        this.onBackToList = onBackToList;
    }


    void open(String initialValue) {
        input.setLength(0);
        error = null;
        loading = false;
        progress = "";
        cliMode = initialValue != null;
        if (initialValue != null) {
            input.append(initialValue);
            submit();
        }
    }

    void handleKey(KeyStroke key) {
        if (loading) {
            return;
        }
        KeyType t = key.getKeyType();
        if (t == KeyType.ESCAPE) {
            input.setLength(0);
            error = null;
            onBackToList.run();
            return;
        }
        if (t == KeyType.ENTER) {
            submit();
            return;
        }
        TextInputs.applyKey(input, key, true);
    }


    private void submit() {
        String trimmed = input.toString().trim();
        if (trimmed.isEmpty()) {
            error = EMPTY_INPUT_ERROR;
            return;
        }
        PluginMarketplacePort.ParsedMarketplaceInput parsed =
            services.plugins().parseMarketplaceInput(trimmed);
        switch (parsed) {
            case PluginMarketplacePort.ParsedMarketplaceInput.Unrecognized _ -> {
                error = INVALID_FORMAT_ERROR;
                if (cliMode) {
                    host.finish("Error: " + INVALID_FORMAT_ERROR);
                }
            }
            case PluginMarketplacePort.ParsedMarketplaceInput.Invalid invalid -> {
                error = invalid.error();
                if (cliMode) {
                    host.finish("Error: " + invalid.error());
                }
            }
            case PluginMarketplacePort.ParsedMarketplaceInput.Parsed ok -> {
                error = null;
                loading = true;
                progress = "";
                services.background().execute(() -> {
                    try {
                        PluginMarketplacePort.AddResult result = services.plugins()
                            .addMarketplace(ok, message -> {
                                synchronized (this) {
                                    progress = message;
                                }
                                host.refresh();
                            });
                        synchronized (this) {
                            loading = false;
                        }
                        if (cliMode) {
                            host.finish("Successfully added marketplace: " + result.name());
                        } else {
                            host.switchToDiscover(result.name(), null);
                        }
                    } catch (Exception e) {
                        synchronized (this) {
                            loading = false;
                            error = e.getMessage();
                        }
                        if (cliMode) {
                            host.finish("Error: " + e.getMessage());
                        }
                    }
                    host.refresh();
                });
            }
        }
    }

    synchronized List<StyledText.Line> buildLines() {
        List<StyledText.Line> lines = new ArrayList<>();
        lines.add(boldLine("Add Marketplace", LanternaTheme.inputText()));
        lines.add(blank());
        lines.add(line("Enter marketplace source:", LanternaTheme.inputText()));
        lines.add(line("Examples:", LanternaTheme.welcomeDim()));
        lines.add(line(" · owner/repo (GitHub)", LanternaTheme.welcomeDim()));
        lines.add(line(" · git@github.com:owner/repo.git (SSH)", LanternaTheme.welcomeDim()));
        lines.add(line(" · https://example.com/marketplace.json", LanternaTheme.welcomeDim()));
        lines.add(line(" · ./path/to/marketplace", LanternaTheme.welcomeDim()));
        lines.add(blank());
        lines.add(line("› " + input + "█", LanternaTheme.inputText()));
        if (loading) {
            lines.add(blank());
            lines.add(line("⠿ " + (progress.isEmpty()
                ? "Adding marketplace to configuration…" : progress), LanternaTheme.claude()));
        }
        if (error != null) {
            lines.add(blank());
            lines.add(line(error, LanternaTheme.toolError()));
        }
        lines.add(blank());
        lines.add(line("Enter to add · Esc to cancel", LanternaTheme.welcomeDim()));
        return lines;
    }

    // ── test accessors ────────────────────────────────────────────────────────

    String error() {
        return error;
    }

    String input() {
        return input.toString();
    }
}
