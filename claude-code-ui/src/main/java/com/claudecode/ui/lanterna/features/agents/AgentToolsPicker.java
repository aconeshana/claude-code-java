package com.claudecode.ui.lanterna.features.agents;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.tools.agent.AgentToolResolver;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Multi-select tool picker for the {@code /agents} create wizard's Tools step and the {@code
 * /agents} panel's Edit-tools quick action — a shared child component (not its own {@link
 * InlineOverlay}), instantiated once per owner ({@link AgentCreateWizard} and {@code AgentsPanel}).
 */
final class AgentToolsPicker extends Panel {

    private static final int LEFT_PAD = 2;

    private sealed interface Item permits Continue, AllTools, BucketRow, AdvancedToggle,
            Header, McpServerRow, ToolRow {}
    private record Continue() implements Item {}
    private record AllTools() implements Item {}
    private record BucketRow(AgentToolResolver.Bucket bucket, List<String> tools) implements Item {}
    private record AdvancedToggle() implements Item {}
    private record Header(String label) implements Item {}
    private record McpServerRow(String serverName, List<String> tools) implements Item {}
    private record ToolRow(String toolName) implements Item {}
    private record McpName(String serverName, String toolName) {}

    private boolean pickerVisible;
    private List<String> availableToolNames = List.of();
    private AgentToolResolver.Bucketed buckets;
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean advancedExpanded;
    private int selectedIdx;
    private Consumer<List<String>> onConfirm;
    private Runnable onCancel;
    private String title = "Create new agent";
    private String subtitle = "Select tools";
    private boolean showFooter = true;
    private TextColor titleColor = LanternaTheme.suggestion();
    private final ContextKeybindingDispatcher keybindings = new ContextKeybindingDispatcher();

    AgentToolsPicker() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        Area area = new Area();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /**
     * Activates the picker.
     */
    void activate(List<String> initialTools, List<String> availableToolNames,
            Consumer<List<String>> onConfirm, Runnable onCancel) {
        activate(initialTools, availableToolNames, onConfirm, onCancel,
            "Create new agent", "Select tools", true, LanternaTheme.suggestion());
    }

    void activate(List<String> initialTools, List<String> availableToolNames,
            Consumer<List<String>> onConfirm, Runnable onCancel,
            String title, String subtitle, boolean showFooter, TextColor titleColor) {
        this.availableToolNames = List.copyOf(availableToolNames);
        this.buckets = AgentToolResolver.bucket(availableToolNames);
        this.selected.clear();
        boolean isAll = initialTools == null || initialTools.contains("*");
        if (isAll) {
            selected.addAll(this.availableToolNames);
        } else {
            selected.addAll(initialTools);
            selected.retainAll(this.availableToolNames);
        }
        this.advancedExpanded = false;
        this.selectedIdx = 0;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.title = title;
        this.subtitle = subtitle;
        this.showFooter = showFooter;
        this.titleColor = titleColor;
        setPickerVisible(true);
    }

    void setPickerVisible(boolean visible) {
        this.pickerVisible = visible;
        invalidate();
    }

    private List<Item> buildItems() {
        List<Item> items = new ArrayList<>();
        items.add(new Continue());
        items.add(new AllTools());
        for (AgentToolResolver.Bucket b : AgentToolResolver.Bucket.values()) {
            List<String> tools = buckets.byBucket().get(b);
            if (tools == null || tools.isEmpty()) continue;
            items.add(new BucketRow(b, tools));
        }
        items.add(new AdvancedToggle());
        if (advancedExpanded) {
            Map<String, List<String>> servers = mcpServerTools();
            if (!servers.isEmpty()) {
                items.add(new Header("MCP servers:"));
                servers.forEach((server, tools) ->
                    items.add(new McpServerRow(server, tools)));
                items.add(new Header("Individual tools:"));
            }
            availableToolNames.forEach(tool -> items.add(new ToolRow(tool)));
        }
        return items;
    }

    private Map<String, List<String>> mcpServerTools() {
        Map<String, List<String>> sorted = new TreeMap<>();
        for (String tool : availableToolNames) {
            McpName mcp = parseMcpName(tool);
            if (mcp != null) {
                sorted.computeIfAbsent(mcp.serverName(), _ -> new ArrayList<>()).add(tool);
            }
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        sorted.forEach((server, tools) -> immutable.put(server, List.copyOf(tools)));
        return immutable;
    }

    private static McpName parseMcpName(String toolName) {
        if (toolName == null || !Strings.CS.startsWith(toolName, "mcp__")) return null;
        String rest = toolName.substring("mcp__".length());
        int separator = rest.indexOf("__");
        if (separator <= 0 || separator >= rest.length() - 2) return null;
        return new McpName(rest.substring(0, separator), rest.substring(separator + 2));
    }

    void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!pickerVisible) return;
        KeyType t = key.getKeyType();
        List<Item> items = buildItems();
        deliver.set(false);
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Confirmation", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && Strings.CS.equals("confirm:no", value)) {
            cancel();
            return;
        }

        if (t == KeyType.ARROW_UP) {
            moveFocus(items, -1);
            invalidate();
            return;
        }
        if (t == KeyType.ARROW_DOWN) {
            moveFocus(items, 1);
            invalidate();
            return;
        }
        if (t == KeyType.ESCAPE) {
            cancel();
            return;
        }
        if (t == KeyType.ENTER) {
            activateItem(items.get(Math.min(selectedIdx, items.size() - 1)));
            invalidate();
        }
    }

    private void moveFocus(List<Item> items, int direction) {
        int candidate = selectedIdx + direction;
        while (candidate >= 0 && candidate < items.size()
                && items.get(candidate) instanceof Header) {
            candidate += direction;
        }
        selectedIdx = Math.max(0, Math.min(items.size() - 1, candidate));
    }

    private void cancel() {
        Runnable cb = onCancel;
        setPickerVisible(false);
        if (cb != null) cb.run();
    }

    private void activateItem(Item item) {
        switch (item) {
            case Continue _ -> confirm();
            case AllTools _ -> {
                if (selected.containsAll(availableToolNames)) selected.clear();
                else selected.addAll(availableToolNames);
            }
            case BucketRow(_, var tools) -> {
                if (selected.containsAll(tools)) tools.forEach(selected::remove);
                else selected.addAll(tools);
            }
            case AdvancedToggle _ -> {
                int toggleIndex = advancedToggleIndex();
                if (advancedExpanded && selectedIdx > toggleIndex) selectedIdx = toggleIndex;
                advancedExpanded = !advancedExpanded;
            }
            case Header _ -> { }
            case McpServerRow(_, var tools) -> {
                if (selected.containsAll(tools)) tools.forEach(selected::remove);
                else selected.addAll(tools);
            }
            case ToolRow(var toolName) -> {
                if (!selected.remove(toolName)) selected.add(toolName);
            }
        }
    }

    private int advancedToggleIndex() {
        List<Item> items = buildItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof AdvancedToggle) return i;
        }
        return 0;
    }

    private void confirm() {
        Consumer<List<String>> cb = onConfirm;
        List<String> result = selected.containsAll(availableToolNames)
            ? null
            : List.copyOf(selected);
        setPickerVisible(false);
        if (cb != null) cb.accept(result);
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!pickerVisible) return new TerminalSize(0, 0);
        return super.calculatePreferredSize();
    }

    @Override public Interactable nextFocus(Interactable fromThis) { return pickerVisible ? super.nextFocus(fromThis) : null; }
    @Override public Interactable previousFocus(Interactable fromThis) { return pickerVisible ? super.previousFocus(fromThis) : null; }

    // ── Test accessors (package-private) ────────────────────────────────────

    boolean isPickerVisible() { return pickerVisible; }
    Set<String> selectedTools() { return Set.copyOf(selected); }
    boolean isAdvancedExpanded() { return advancedExpanded; }
    int itemCount() { return buildItems().size(); }
    List<String> advancedLabels() {
        List<Item> items = buildItems();
        int toggle = advancedToggleIndex();
        return items.subList(Math.min(toggle + 1, items.size()), items.size()).stream()
            .map(this::label)
            .toList();
    }

    // ──────────────────────────────────────────────────────────────────────────

    private final class Area extends AbstractComponent<Area> {
        @Override protected ComponentRenderer<Area> createDefaultRenderer() { return new Renderer(); }
    }

    private final class Renderer implements ComponentRenderer<Area> {

        @Override
        public TerminalSize getPreferredSize(Area c) {
            if (!pickerVisible) return new TerminalSize(0, 0);
            return new TerminalSize(70, renderedRowCount(buildItems()));
        }

        @Override
        public void drawComponent(TextGUIGraphics g, Area c) {
            if (!pickerVisible) return;
            g.fill(' ');

            if (title != null) {
                g.setForegroundColor(titleColor);
                g.enableModifiers(SGR.BOLD);
                g.putString(LEFT_PAD, 0, title);
                g.disableModifiers(SGR.BOLD);
                if (subtitle != null) {
                    g.setForegroundColor(LanternaTheme.ghostText());
                    g.putString(LEFT_PAD, 1, subtitle);
                }
            }

            List<Item> items = buildItems();
            int row = contentStartRow();
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                if (i == 1 || item instanceof AdvancedToggle) {
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(LEFT_PAD, row++, "─".repeat(40));
                }
                if (item instanceof Header && i > 1) row++;
                boolean isSelected = i == selectedIdx;
                boolean header = item instanceof Header;
                g.setForegroundColor(header ? LanternaTheme.ghostText()
                    : isSelected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                String pointer = header ? "" : isSelected ? "❯ " : "  ";
                g.putString(LEFT_PAD, row++, pointer + label(item));
            }

            row++;
            g.setForegroundColor(LanternaTheme.ghostText());
            g.putString(LEFT_PAD, row++, selectionSummary());

            if (showFooter) {
                int footerRow = row + 1;
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.enableModifiers(SGR.ITALIC);
                g.putString(LEFT_PAD, footerRow,
                    "Enter toggle selection · ↑↓ navigate · Esc go back");
                g.disableModifiers(SGR.ITALIC);
            }
        }
    }

    private int renderedRowCount(List<Item> items) {
        int rows = contentStartRow() + items.size() + 2 + (showFooter ? 2 : 0);
        if (items.size() > 1) rows++;
        if (items.stream().anyMatch(AdvancedToggle.class::isInstance)) rows++;
        rows += (int) items.stream().filter(Header.class::isInstance).count();
        return rows;
    }

    private int contentStartRow() {
        return title == null ? 0 : subtitle == null ? 2 : 3;
    }

    private String label(Item item) {
        return switch (item) {
            case Continue _ -> "[ Continue ]";
            case AllTools _ -> checkbox(selected.containsAll(availableToolNames)
                && !availableToolNames.isEmpty()) + " All tools";
            case BucketRow(var bucket, var tools) -> checkbox(selected.containsAll(tools))
                + " " + bucketName(bucket);
            case AdvancedToggle _ -> "[ " + (advancedExpanded ? "Hide" : "Show")
                + " advanced options ]";
            case Header(var text) -> text;
            case McpServerRow(var server, var tools) -> checkbox(selected.containsAll(tools))
                + " " + server + " (" + tools.size() + " "
                + (tools.size() == 1 ? "tool" : "tools") + ")";
            case ToolRow(var toolName) -> checkbox(selected.contains(toolName))
                + " " + displayToolName(toolName);
        };
    }

    private String selectionSummary() {
        return selected.containsAll(availableToolNames) && !availableToolNames.isEmpty()
            ? "All tools selected"
            : selected.size() + " of " + availableToolNames.size() + " tools selected";
    }

    private String displayToolName(String toolName) {
        McpName mcp = parseMcpName(toolName);
        return mcp == null ? toolName : mcp.toolName() + " (" + mcp.serverName() + ")";
    }

    private String checkbox(boolean checked) { return checked ? "☒" : "☐"; }

    private String bucketName(AgentToolResolver.Bucket b) {
        return switch (b) {
            case READ_ONLY -> "Read-only tools";
            case EDIT -> "Edit tools";
            case EXECUTION -> "Execution tools";
            case MCP -> "MCP tools";
            case OTHER -> "Other tools";
        };
    }
}
