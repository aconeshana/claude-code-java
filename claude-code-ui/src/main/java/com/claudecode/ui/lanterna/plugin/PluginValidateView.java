package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static com.claudecode.ui.lanterna.components.StyledText.blank;
import static com.claudecode.ui.lanterna.components.StyledText.boldLine;
import static com.claudecode.ui.lanterna.components.StyledText.line;
import static com.claudecode.core.text.StringUtils.plural;

import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.components.StyledText;
import com.claudecode.ui.lanterna.input.TextInputs;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * {@code /plugin validate} view: run {@link PluginMarketplacePort#validate} against a manifest file
 * or plugin directory and show the errors/warnings report.
 */
final class PluginValidateView {

    enum Mode { INPUT, RUNNING, RESULT }

    private final PluginPanelServices services;
    private final BiConsumer<String, TextColor> recorder;
    private final Runnable closePanel;
    private final Runnable invalidate;

    private Mode mode = Mode.INPUT;
    private final StringBuilder pathInput = new StringBuilder();
    private List<String> resultLines = List.of();
    private boolean resultSuccess;

    PluginValidateView(PluginPanelServices services,
                       BiConsumer<String, TextColor> recorder,
                       Runnable closePanel,
                       Runnable invalidate) {
        this.services = services;
        this.recorder = recorder;
        this.closePanel = closePanel;
        this.invalidate = invalidate;
    }

    /** Opens the view; a non-null path starts validating immediately. */
    void open(String path) {
        if (StringUtils.isBlank(path)) {
            mode = Mode.INPUT;
            pathInput.setLength(0);
        } else {
            runValidation(path.trim());
        }
    }

    void handleKey(KeyStroke key) {
        KeyType t = key.getKeyType();
        if (t == KeyType.ESCAPE) {
            closePanel.run();
            return;
        }
        if (mode == Mode.RESULT && t == KeyType.ENTER) {
            closePanel.run();
            return;
        }
        if (mode != Mode.INPUT) {
            return;
        }
        if (t == KeyType.ENTER) {
            String path = pathInput.toString().trim();
            if (!path.isEmpty()) {
                runValidation(path);
            }
            return;
        }
        TextInputs.applyKey(pathInput, key, true);
    }

    private void runValidation(String path) {
        mode = Mode.RUNNING;
        services.background().execute(() -> {
            List<String> output;
            boolean success;
            try {
                Path target = Path.of(path);
                PluginMarketplacePort.ValidationResult result = services.plugins().validate(target);
                output = formatResult(result);
                success = result.success();
            } catch (Exception e) {
                output = List.of("✖ Unexpected error during validation: " + e.getMessage());
                success = false;
            }
            synchronized (this) {
                resultLines = output;
                resultSuccess = success;
                mode = Mode.RESULT;
            }

            for (String lineText : output) {
                recorder.accept(lineText, LanternaTheme.inputText());
            }
            invalidate.run();
        });
    }


    static List<String> formatResult(PluginMarketplacePort.ValidationResult result) {
        List<String> out = new ArrayList<>();
        out.add("Validating " + result.fileType() + " manifest: " + result.filePath());
        out.add("");
        if (!result.errors().isEmpty()) {
            out.add("✖ Found " + result.errors().size() + " "
                + plural(result.errors().size(), "error") + ":");
            out.add("");
            for (PluginMarketplacePort.ValidationError error : result.errors()) {
                out.add("  ❯ " + error.path() + ": " + error.message());
            }
            out.add("");
        }
        if (!result.warnings().isEmpty()) {
            out.add("⚠ Found " + result.warnings().size() + " "
                + plural(result.warnings().size(), "warning") + ":");
            out.add("");
            for (PluginMarketplacePort.ValidationWarning warning : result.warnings()) {
                out.add("  ❯ " + warning.path() + ": " + warning.message());
            }
            out.add("");
        }
        if (result.success()) {
            out.add(result.warnings().isEmpty()
                ? "✔ Validation passed"
                : "✔ Validation passed with warnings");
        } else {
            out.add("✖ Validation failed");
        }
        return out;
    }

    synchronized List<StyledText.Line> buildLines() {
        List<StyledText.Line> lines = new ArrayList<>();
        switch (mode) {
            case INPUT -> {
                lines.add(boldLine("Validate plugin", LanternaTheme.inputText()));
                lines.add(line("Validate a plugin or marketplace manifest file or directory.",
                    LanternaTheme.welcomeDim()));
                lines.add(blank());
                lines.add(line("Examples:", LanternaTheme.welcomeDim()));
                lines.add(line("  /plugin validate .claude-plugin/plugin.json", LanternaTheme.welcomeDim()));
                lines.add(line("  /plugin validate /path/to/plugin-directory", LanternaTheme.welcomeDim()));
                lines.add(line("  /plugin validate .", LanternaTheme.welcomeDim()));
                lines.add(blank());
                lines.add(line("› " + pathInput + "█", LanternaTheme.inputText()));
                lines.add(blank());
                lines.add(line("Enter to validate · Esc to cancel", LanternaTheme.welcomeDim()));
            }
            case RUNNING -> lines.add(line("Running validation...", LanternaTheme.inputText()));
            case RESULT -> {
                for (String text : resultLines) {
                    TextColor color = Strings.CS.startsWith(text, "✖") ? LanternaTheme.toolError()
                        : Strings.CS.startsWith(text, "⚠") ? LanternaTheme.toolWarning()
                        : Strings.CS.startsWith(text, "✔") ? LanternaTheme.toolSuccess()
                        : LanternaTheme.inputText();
                    lines.add(line(text, color));
                }
                lines.add(blank());
                lines.add(line("Enter/Esc to close", LanternaTheme.welcomeDim()));
            }
        }
        return lines;
    }

    // ── test accessors ────────────────────────────────────────────────────────

    Mode mode() {
        return mode;
    }

    boolean resultSuccess() {
        return resultSuccess;
    }

    String pathInput() {
        return pathInput.toString();
    }
}
