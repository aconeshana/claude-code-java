package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.EnvUtils;
import com.claudecode.runtime.plugins.PluginMarketplacePort.ConfigOption;
import com.claudecode.ui.lanterna.input.TextInputs;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.claudecode.ui.lanterna.components.StyledText.blank;
import static com.claudecode.ui.lanterna.components.StyledText.bold;
import static com.claudecode.ui.lanterna.components.StyledText.boldLine;
import static com.claudecode.ui.lanterna.components.StyledText.line;
import static com.claudecode.ui.lanterna.components.StyledText.seg;
import com.claudecode.ui.lanterna.components.StyledText;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Sequential per-field config prompt walked after install/enable or from the "Configure options"
 * menu — one text field at a time, Tab to skip ahead, Enter to save-and-continue
 * (save-configuration on the last field).
 */
final class PluginOptionsFlowView {

/** Outcome callbacks — matches {@code PluginOptionsDialog} onSave/onCancel. */
    interface Listener {

        /** All fields collected; values are String / Boolean / Double per schema type. */
        void onSave(Map<String, Object> values);

        void onCancel();
    }

    private final String title;
    private final String subtitle;
    private final LinkedHashMap<String, ConfigOption> schema;
    private final Map<String, Object> initialValues;
    private final Listener listener;

    private final List<String> fields;
    private int currentFieldIndex;
    private final Map<String, String> collected = new LinkedHashMap<>();
    private StringBuilder currentInput;
    private String error;
    private boolean saving;

    PluginOptionsFlowView(String title, String subtitle,
                          LinkedHashMap<String, ConfigOption> schema,
                          Map<String, Object> initialValues,
                          Listener listener) {
        this.title = title;
        this.subtitle = subtitle;
        this.schema = schema;
        this.initialValues = initialValues == null ? Map.of() : initialValues;
        this.listener = listener;
        this.fields = new ArrayList<>(schema.keySet());
        this.currentInput = new StringBuilder(fields.isEmpty() ? "" : initialFor(fields.getFirst()));
    }

    boolean isEmpty() {
        return fields.isEmpty();
    }


    private String initialFor(String key) {
        ConfigOption option = schema.get(key);
        if (option != null && Boolean.TRUE.equals(option.sensitive())) {
            return "";
        }
        Object v = initialValues.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    void handleKey(KeyStroke key) {
        if (saving) return;
        KeyType t = key.getKeyType();
        if (t == KeyType.ESCAPE) {
            listener.onCancel();
            return;
        }
        if (t == KeyType.TAB) {
            nextField();
            return;
        }
        if (t == KeyType.ENTER) {
            confirm();
            return;
        }
        // Consume BACKSPACE/PASTE/CHARACTER as text input (PASTE is swallowed so it
        // can't leak into the main input behind the panel — no real GUI focus here).
        TextInputs.applyKey(currentInput, key, false);
    }


    private void nextField() {
        if (currentFieldIndex < fields.size() - 1) {
            collected.put(fields.get(currentFieldIndex), currentInput.toString());
            currentFieldIndex++;
            currentInput = new StringBuilder(initialFor(fields.get(currentFieldIndex)));
        }
    }


    private void confirm() {
        if (fields.isEmpty()) {
            return;
        }
        collected.put(fields.get(currentFieldIndex), currentInput.toString());
        if (currentFieldIndex < fields.size() - 1) {
            currentFieldIndex++;
            currentInput = new StringBuilder(initialFor(fields.get(currentFieldIndex)));
            return;
        }
        Map<String, Object> finalValues = buildFinalValues();
        String missing = firstMissingRequired(finalValues);
        if (missing != null) {
            ConfigOption option = schema.get(missing);
            String label = option != null && option.title() != null ? option.title() : missing;
            error = label + " is required";
            currentFieldIndex = fields.indexOf(missing);
            currentInput = new StringBuilder(initialFor(missing));
            return;
        }
        listener.onSave(finalValues);
    }

    void setSaving(boolean saving) {
        this.saving = saving;
        if (saving) error = null;
    }

    void setError(String error) {
        this.saving = false;
        this.error = error;
    }


    private Map<String, Object> buildFinalValues() {
        Map<String, Object> finalValues = new LinkedHashMap<>();
        for (String fieldKey : fields) {
            ConfigOption option = schema.get(fieldKey);
            String value = collected.getOrDefault(fieldKey, "");
            boolean sensitive = option != null && Boolean.TRUE.equals(option.sensitive());
            if (sensitive && value.isEmpty() && initialValues.get(fieldKey) != null) {
                continue; // keep existing secret — omit key entirely
            }
            String type = option == null ? null : option.type();
            if (Strings.CS.equals("number", type)) {
                if (value.trim().isEmpty()) {
                    continue;
                }
                try {
                    finalValues.put(fieldKey, Double.parseDouble(value.trim()));
                } catch (NumberFormatException _) {
                    finalValues.put(fieldKey, value);
                }
            } else if (Strings.CS.equals("boolean", type)) {
                finalValues.put(fieldKey, EnvUtils.isEnvTruthy(value));
            } else {
                finalValues.put(fieldKey, value);
            }
        }
        return finalValues;
    }


    private String firstMissingRequired(Map<String, Object> finalValues) {
        for (String fieldKey : fields) {
            ConfigOption option = schema.get(fieldKey);
            if (option == null || !Boolean.TRUE.equals(option.required())
                    || Strings.CS.equals("boolean", option.type())) {
                continue;
            }
            Object value = finalValues.get(fieldKey);
            boolean blank = value == null || (value instanceof String s && StringUtils.isBlank(s));
            if (blank && initialValues.get(fieldKey) == null) {
                return fieldKey;
            }
        }
        return null;
    }

    List<StyledText.Line> buildLines() {
        List<StyledText.Line> lines = new ArrayList<>();
        lines.add(boldLine(title, LanternaTheme.inputText()));
        lines.add(line(subtitle, LanternaTheme.welcomeDim()));
        lines.add(blank());

        String currentField = fields.get(currentFieldIndex);
        ConfigOption option = schema.get(currentField);
        boolean sensitive = option != null && Boolean.TRUE.equals(option.sensitive());
        boolean required = option != null && Boolean.TRUE.equals(option.required());

        String label = option != null && option.title() != null ? option.title() : currentField;
        if (required) {
            lines.add(line(bold(label, LanternaTheme.inputText()),
                seg(" *", LanternaTheme.toolError())));
        } else {
            lines.add(boldLine(label, LanternaTheme.inputText()));
        }
        if (option != null && option.description() != null && !option.description().isEmpty()) {
            lines.add(line(option.description(), LanternaTheme.welcomeDim()));
        }
        String display = sensitive
            ? "*".repeat(currentInput.length())
            : currentInput.toString();
        lines.add(blank());
        lines.add(line("› " + display + (saving ? "" : "█"), LanternaTheme.inputText()));
        if (sensitive) {
            lines.add(line("⚠ Sensitive value — stored in secure credentials storage",
                LanternaTheme.toolWarning()));
        }
        if (error != null) {
            lines.add(line(error, LanternaTheme.toolError()));
        }
        if (saving) {
            lines.add(line("Saving…", LanternaTheme.welcomeDim()));
        }
        lines.add(blank());
        lines.add(line("Field " + (currentFieldIndex + 1) + " of " + fields.size(),
            LanternaTheme.welcomeDim()));
        if (currentFieldIndex < fields.size() - 1) {
            lines.add(line("Tab: Next field · Enter: Save and continue", LanternaTheme.welcomeDim()));
        } else {
            lines.add(line("Enter: Save configuration", LanternaTheme.welcomeDim()));
        }
        return lines;
    }

    // ── test accessors ────────────────────────────────────────────────────────

    int currentFieldIndex() {
        return currentFieldIndex;
    }

    String error() {
        return error;
    }
}
