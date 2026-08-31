package com.claudecode.ui.lanterna.features.settings;

import org.apache.commons.lang3.Strings;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.RuleSource;
import com.claudecode.permissions.ToolPermissionContext;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import com.claudecode.ui.lanterna.dialog.AddDirDialog;


class WorkspaceTabTest {

    @TempDir Path tempDir;

    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);

    private static void send(WorkspaceTab t, KeyStroke k) {
        t.handleKey(k, new AtomicBoolean(true));
    }

    private WorkspaceTab tabWithDirs(PermissionGate gate) {
        WorkspaceTab t = new WorkspaceTab();
        t.bind(() -> gate, () -> tempDir.toString(),
            path -> new AddDirDialog.ValidationOutcome(path, null),
            (_, _) -> {});
        t.setTabVisible(true);
        t.reload();
        return t;
    }

    @Test
    void reload_loadsAdditionalDirsFromGate() {
        Path dir = tempDir.resolve("project-a");
        PermissionGate gate = new PermissionGate(ToolPermissionContext.builder()
            .workingDirectory(tempDir)
            .additionalDirs(Map.of(dir, RuleSource.SESSION))
            .build());
        WorkspaceTab t = tabWithDirs(gate);
        assertEquals(List.of(dir), t.directories());
    }

    @Test
    void workspaceListRendersSuppliedOriginalWorkingDirectory() {
        PermissionGate gate = new PermissionGate(ToolPermissionContext.of(tempDir));
        WorkspaceTab t = new WorkspaceTab();
        t.bind(() -> gate, () -> "/workspace",
            path -> new AddDirDialog.ValidationOutcome(path, null), (_, _) -> {});
        t.setTabVisible(true);
        t.reload();
        TerminalSize size = t.calculatePreferredSize();
        t.setSize(size);
        BasicTextImage image = new BasicTextImage(size);

        t.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));

        String rendered = renderedText(image);
        assertTrue(Strings.CS.contains(rendered, "-  /workspace"));
        assertTrue(Strings.CS.contains(rendered, "(Original working"));
        assertTrue(Strings.CS.contains(rendered, "directory)"));
    }

    @Test
    void directoryList_capsVisibleOptionsAtTen() {
        Map<Path, RuleSource> dirs = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            dirs.put(tempDir.resolve("project-" + i), RuleSource.SESSION);
        }
        PermissionGate gate = new PermissionGate(ToolPermissionContext.builder()
            .workingDirectory(tempDir)
            .additionalDirs(dirs)
            .build());
        WorkspaceTab t = tabWithDirs(gate);


        assertEquals(18, t.getPreferredSize().getRows());
    }

    @Test
    void directoryList_scrollsToKeepSelectionInsideTenOptionWindow() {
        Map<Path, RuleSource> dirs = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            dirs.put(tempDir.resolve("project-" + i), RuleSource.SESSION);
        }
        WorkspaceTab t = tabWithDirs(new PermissionGate(ToolPermissionContext.builder()
            .workingDirectory(tempDir)
            .additionalDirs(dirs)
            .build()));

        for (int i = 0; i < 10; i++) send(t, DOWN);

        assertEquals(10, t.selectedIndex());
        assertEquals(1, t.scrollOffset());
    }

    @Test
    void enterOnAddRow_opensEmbeddedAddDirDialog() {
        PermissionGate gate = new PermissionGate(ToolPermissionContext.of(tempDir));
        WorkspaceTab t = tabWithDirs(gate);
        send(t, ENTER); // only row is "+ Add directory..."
        assertTrue(t.addDirDialog().isActive());
    }

    @Test
    void addDirDialogResult_appliedViaCallbackAndRefreshesList() {
        Path dir = tempDir.resolve("new-dir");
        AtomicReference<String> appliedPath = new AtomicReference<>();
        PermissionGate gate = new PermissionGate(ToolPermissionContext.of(tempDir));
        WorkspaceTab t = new WorkspaceTab();
        t.bind(() -> gate, () -> tempDir.toString(),
            path -> new AddDirDialog.ValidationOutcome(path, null),
            (path, _) -> {
                appliedPath.set(path);
                gate.addDirectories(List.of(Path.of(path)));
            });
        t.setTabVisible(true);
        t.reload();

        send(t, ENTER); // open AddDirDialog
        // Drive AddDirDialog's INPUT phase directly to a submit.
        for (char c : dir.toString().toCharArray()) {
            t.addDirDialog().handleKey(new KeyStroke(c, false, false), new AtomicBoolean(true));
        }
        t.addDirDialog().handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals(dir.toString(), appliedPath.get());
        assertTrue(t.directories().contains(dir), "list must refresh after the dialog resolves");
    }

    @Test
    void removeDirectory_confirmedYes_removesFromGateOnly() throws Exception {
        Path dir = tempDir.resolve("project-a");
        Files.createDirectories(dir);
        Path localSettings = tempDir.resolve(".claude").resolve("settings.local.json");
        Files.createDirectories(localSettings.getParent());
        Files.writeString(localSettings,
            "{\"permissions\": {\"additionalDirectories\": [\"" + dir + "\"]}}");

        PermissionGate gate = new PermissionGate(ToolPermissionContext.builder()
            .workingDirectory(tempDir)
            .additionalDirs(Map.of(dir, RuleSource.SESSION))
            .build());
        WorkspaceTab t = tabWithDirs(gate);

        send(t, ENTER); // open remove-confirm on the only directory row (index 0)
        assertEquals(WorkspaceTab.Mode.REMOVE_CONFIRM, t.mode());
        send(t, ENTER);

        assertEquals(WorkspaceTab.Mode.LIST, t.mode());
        assertTrue(gate.currentContext().additionalDirs().isEmpty(), "removed from in-memory context");
        assertTrue(Strings.CS.contains(Files.readString(localSettings), dir.toString()),
            "settings.json must NOT be touched — TS RemoveWorkspaceDirectory is session-only");
    }

    @Test
    void removeDirectory_confirmedNo_keepsDirectory() {
        Path dir = tempDir.resolve("project-a");
        PermissionGate gate = new PermissionGate(ToolPermissionContext.builder()
            .workingDirectory(tempDir)
            .additionalDirs(Map.of(dir, RuleSource.SESSION))
            .build());
        WorkspaceTab t = tabWithDirs(gate);

        send(t, ENTER); // REMOVE_CONFIRM, defaults to "Yes"
        send(t, DOWN);  // select "No"
        send(t, ENTER); // confirm "No"

        assertEquals(WorkspaceTab.Mode.LIST, t.mode());
        assertTrue(gate.currentContext().additionalDirs().containsKey(dir));
    }

    @Test
    void escFromList_requestsClose() {
        PermissionGate gate = new PermissionGate(ToolPermissionContext.of(tempDir));
        WorkspaceTab t = tabWithDirs(gate);
        AtomicBoolean closed = new AtomicBoolean(false);
        t.setOnCloseRequest(() -> closed.set(true));
        send(t, ESC);
        assertTrue(closed.get());
    }

    @Test
    void upAtTopOfList_requestsHeaderFocus() {
        PermissionGate gate = new PermissionGate(ToolPermissionContext.of(tempDir));
        WorkspaceTab t = tabWithDirs(gate);
        AtomicBoolean requested = new AtomicBoolean(false);
        t.setOnFocusHeaderRequest(() -> requested.set(true));
        send(t, UP);
        assertTrue(requested.get());
    }



    private record Recorded(String text, TextColor color) {}

    @Test
    void addDirectory_recordsChangeLine() {
        AtomicReference<String> appliedPath = new AtomicReference<>();
        PermissionGate gate = new PermissionGate(ToolPermissionContext.of(tempDir));
        WorkspaceTab t = new WorkspaceTab();
        t.bind(() -> gate, () -> tempDir.toString(),
            path -> new AddDirDialog.ValidationOutcome(path, null),
            (path, _) -> {
                appliedPath.set(path);
                gate.addDirectories(List.of(Path.of(path)));
            });
        List<Recorded> recorded = new ArrayList<>();
        t.setChangeRecorder((text, color) -> recorded.add(new Recorded(text, color)));
        t.setTabVisible(true);
        t.reload();

        Path dir = tempDir.resolve("new-dir");
        send(t, ENTER); // open AddDirDialog
        for (char c : dir.toString().toCharArray()) {
            t.addDirDialog().handleKey(new KeyStroke(c, false, false), new AtomicBoolean(true));
        }
        t.addDirDialog().handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals(1, recorded.size());
        assertEquals("Added directory " + dir + " to workspace for this session", recorded.getFirst().text());
    }

    @Test
    void removeDirectory_confirmedYes_recordsChangeLine() {
        Path dir = tempDir.resolve("project-a");
        PermissionGate gate = new PermissionGate(ToolPermissionContext.builder()
            .workingDirectory(tempDir)
            .additionalDirs(Map.of(dir, RuleSource.SESSION))
            .build());
        WorkspaceTab t = tabWithDirs(gate);
        List<Recorded> recorded = new ArrayList<>();
        t.setChangeRecorder((text, color) -> recorded.add(new Recorded(text, color)));

        send(t, ENTER); // REMOVE_CONFIRM
        send(t, ENTER);

        assertEquals(1, recorded.size());
        assertEquals("Removed directory " + dir + " from workspace", recorded.getFirst().text());
    }

    @Test
    void removeDirectory_confirmedNo_recordsNoChangeLine() {
        Path dir = tempDir.resolve("project-a");
        PermissionGate gate = new PermissionGate(ToolPermissionContext.builder()
            .workingDirectory(tempDir)
            .additionalDirs(Map.of(dir, RuleSource.SESSION))
            .build());
        WorkspaceTab t = tabWithDirs(gate);
        List<Recorded> recorded = new ArrayList<>();
        t.setChangeRecorder((text, color) -> recorded.add(new Recorded(text, color)));

        send(t, ENTER); // REMOVE_CONFIRM, defaults to "Yes"
        send(t, DOWN);  // select "No"
        send(t, ENTER); // confirm "No"

        assertEquals(0, recorded.size());
    }

    private static String renderedText(BasicTextImage image) {
        StringBuilder text = new StringBuilder();
        TerminalSize size = image.getSize();
        for (int row = 0; row < size.getRows(); row++) {
            for (int column = 0; column < size.getColumns(); column++) {
                text.append(image.getCharacterAt(column, row).getCharacterString());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
