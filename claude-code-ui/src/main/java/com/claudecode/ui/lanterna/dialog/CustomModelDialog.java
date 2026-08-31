package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.ui.lanterna.input.TextInputs;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Inline form for one user-defined model endpoint.
 *
 * Captures the model name, protocol, base URL, optional API key, context
 * window, and custom HTTP headers. Up/down navigation cycles between fields;
 * Enter advances to the next field.
 */
@Explanation("Interactive editor for the multi-protocol model catalogue")
public final class CustomModelDialog extends Panel implements InlineOverlay {
    private static final int LEFT_PAD = 2;
    private static final int MIN_WIDTH = 76;
    private static final ModelApiProtocol[] PROTOCOLS = {
        ModelApiProtocol.OPENAI_RESPONSES,
        ModelApiProtocol.OPENAI_CHAT,
        ModelApiProtocol.ANTHROPIC
    };

    private boolean active;
    private int field;
    private int protocolIndex;
    private StringBuilder modelName = new StringBuilder();
    private StringBuilder baseUrl = new StringBuilder();
    private StringBuilder apiKey = new StringBuilder();
    private StringBuilder contextWindow = new StringBuilder();
    private StringBuilder headers = new StringBuilder();
    private String errorMessage;
    private Consumer<CustomModelConfig> onResult;

    public CustomModelDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        FormArea area = new FormArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    public synchronized void show(Consumer<CustomModelConfig> onResult) {
        this.field = 0;
        this.protocolIndex = 0;
        this.modelName = new StringBuilder();
        this.baseUrl = new StringBuilder();
        this.apiKey = new StringBuilder();
        this.contextWindow = new StringBuilder();
        this.headers = new StringBuilder();
        this.errorMessage = null;
        this.onResult = onResult;
        this.active = true;
        invalidate();
    }

    @Override public boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        KeyType type = key.getKeyType();
        if (type == KeyType.ESCAPE || isCtrlCancel(key)) {
            resolve(null);
            deliver.set(false);
            return;
        }
        if (field == 1 && (type == KeyType.ARROW_LEFT || type == KeyType.ARROW_RIGHT
                || (type == KeyType.CHARACTER && Character.valueOf(' ').equals(key.getCharacter())))) {
            int delta = type == KeyType.ARROW_LEFT ? -1 : 1;
            protocolIndex = InlineOverlay.cycleIndex(protocolIndex, delta, PROTOCOLS.length);
            errorMessage = null;
            invalidate();
            deliver.set(false);
            return;
        }
        if (type == KeyType.ARROW_UP || type == KeyType.ARROW_DOWN) {
            int delta = type == KeyType.ARROW_UP ? -1 : 1;
            field = InlineOverlay.cycleIndex(field, delta, 6);
            errorMessage = null;
            invalidate();
            deliver.set(false);
            return;
        }
        if (type == KeyType.ENTER) {
            if (field < 5) {
                field++;
                errorMessage = null;
                invalidate();
            } else {
                submit();
            }
            deliver.set(false);
            return;
        }
        StringBuilder target = switch (field) {
            case 0 -> modelName;
            case 2 -> baseUrl;
            case 3 -> apiKey;
            case 4 -> contextWindow;
            case 5 -> headers;
            default -> null;
        };
        if (target != null && TextInputs.tryApplyKey(target, key, false)) {
            errorMessage = null;
            invalidate();
            deliver.set(false);
        }
    }

    String errorMessage() {
        return errorMessage;
    }

    private void submit() {
        try {
            CustomModelConfig config = new CustomModelConfig(
                modelName.toString(), PROTOCOLS[protocolIndex], baseUrl.toString(),
                apiKey.toString(), parseHeaders(headers.toString()), parseContextWindow(contextWindow.toString()));
            resolve(config);
        } catch (IllegalArgumentException e) {
            errorMessage = e.getMessage() != null ? e.getMessage() : "Invalid model configuration";
            invalidate();
        }
    }

    private static Long parseContextWindow(String raw) {
        if (StringUtils.isBlank(raw)) return null;
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException("Context window must be a token count");
        }
    }

    private static Map<String, String> parseHeaders(String raw) {
        if (StringUtils.isBlank(raw)) return Map.of();
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String item : raw.split(";")) {
            if (StringUtils.isBlank(item)) continue;
            int separator = item.indexOf(':');
            if (separator <= 0) {
                throw new IllegalArgumentException("Headers must use Name: Value; Name2: Value2");
            }
            parsed.put(item.substring(0, separator).trim(), item.substring(separator + 1).trim());
        }
        return parsed;
    }

    private synchronized void resolve(CustomModelConfig result) {
        if (!active) return;
        Consumer<CustomModelConfig> callback = onResult;
        active = false;
        onResult = null;
        invalidate();
        if (callback != null) callback.accept(result);
    }

    private static boolean isCtrlCancel(KeyStroke key) {
        return key.getKeyType() == KeyType.CHARACTER && key.isCtrlDown()
            && (Character.valueOf('c').equals(key.getCharacter())
                || Character.valueOf('d').equals(key.getCharacter()));
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        return active ? new TerminalSize(MIN_WIDTH, 13) : new TerminalSize(0, 0);
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    private final class FormArea extends AbstractComponent<FormArea> {
        @Override protected ComponentRenderer<FormArea> createDefaultRenderer() {
            return new FormRenderer();
        }
    }

    private final class FormRenderer implements ComponentRenderer<FormArea> {
        @Override public TerminalSize getPreferredSize(FormArea component) {
            return active ? new TerminalSize(MIN_WIDTH, 13) : new TerminalSize(0, 0);
        }

        @Override public void drawComponent(TextGUIGraphics g, FormArea component) {
            if (!active) return;
            g.fill(' ');
            int width = g.getSize().getColumns();
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, width)));
            g.setForegroundColor(LanternaTheme.remember());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Add custom model");
            g.disableModifiers(SGR.BOLD);
            drawField(g, 3, 0, "Model name", modelName.toString());
            drawField(g, 4, 1, "Protocol", "← " + PROTOCOLS[protocolIndex].displayName() + " →");
            drawField(g, 5, 2, "Base URL", baseUrl.toString());
            drawField(g, 6, 3, "API key", apiKey.isEmpty() ? "(optional)" : "•".repeat(apiKey.length()));
            drawField(g, 7, 4, "Context window", contextWindow.isEmpty() ? "(optional; model default)" : contextWindow.toString());
            drawField(g, 8, 5, "Headers", headers.isEmpty() ? "(optional; Name: Value; …)" : headers.toString());
            if (errorMessage != null) {
                g.setForegroundColor(LanternaTheme.toolError());
                g.putString(LEFT_PAD, 10, InlineOverlay.clip(errorMessage, width - LEFT_PAD));
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, 12, "↑ ↓ fields · Enter next/save · ← → protocol · Esc cancel");
            g.disableModifiers(SGR.ITALIC);
        }

        private void drawField(TextGUIGraphics g, int row, int index, String label, String value) {
            g.setForegroundColor(index == field ? LanternaTheme.suggestion() : LanternaTheme.inputText());
            g.putString(LEFT_PAD, row, (index == field ? "❯ " : "  ") + label + ": ");
            int x = LEFT_PAD + label.length() + 4;
            g.setForegroundColor(Strings.CS.startsWith(value, "(")
                ? LanternaTheme.ghostText() : LanternaTheme.inputText());
            g.putString(x, row, InlineOverlay.clip(value, Math.max(0, g.getSize().getColumns() - x)));
        }
    }
}
