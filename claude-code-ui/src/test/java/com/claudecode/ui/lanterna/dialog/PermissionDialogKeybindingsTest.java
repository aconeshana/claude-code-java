package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Runtime keybinding coverage for the generic permission prompt. */
class PermissionDialogKeybindingsTest {

    @Test
    void selectAcceptCanBeReboundAndEscapeNullUnbound(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [{"context":"Select","bindings":{
              "x":"select:accept",
              "escape":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            AtomicReference<PermissionAskCallback.Result> result = new AtomicReference<>();
            PermissionDialog dialog = new PermissionDialog();
            dialog.setKeybindingsStore(store);
            dialog.show(PermissionPreviewPreparer.standard().prepare(
                    PermissionAskContext.simple("Bash", null, "tool-1")), null,
                _ -> {}, result::set, () -> {});

            dialog.handleYesButtonKeyForTest(new KeyStroke(KeyType.ESCAPE));
            assertTrue(dialog.isActiveForTest());
            assertNull(result.get());

            dialog.handleYesButtonKeyForTest(new KeyStroke('x', false, false));
            assertFalse(dialog.isActiveForTest());
            assertNotNull(result.get());
            assertTrue(result.get().allowed());
        } finally {
            store.dispose();
        }
    }

    @Test
    void permissionDebugActionCanBeRebound(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [{"context":"Confirmation","bindings":{
              "x":"permission:toggleDebug",
              "ctrl+d":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            PermissionDialog dialog = new PermissionDialog();
            dialog.setKeybindingsStore(store);
            dialog.show(PermissionPreviewPreparer.standard().prepare(
                    PermissionAskContext.builder("Bash", null)
                        .toolUseId("tool-1")
                        .decisionReason("rule", "Bash(git:*)")
                        .suggestion("git:*", "git commands")
                        .build()),
                null, _ -> {}, _ -> {}, () -> {});

            dialog.handleYesButtonKeyForTest(new KeyStroke('d', true, false));
            assertFalse(dialog.debugVisibleForTest(), "null-unbound Ctrl+D must not use a hard-coded fallback");

            dialog.handleYesButtonKeyForTest(new KeyStroke('x', false, false));
            assertTrue(dialog.debugVisibleForTest());
        } finally {
            store.dispose();
        }
    }

    @Test
    void bashDirectorySuggestionUsesReleased197LabelAndReturnsAllUpdates() {
        PermissionDialog dialog = new PermissionDialog();
        AtomicReference<List<PermissionUpdate>> applied = new AtomicReference<>();
        List<PermissionUpdate> suggestions = List.of(
            new PermissionUpdate.AddDirectories(
                List.of("/private/tmp"), PermissionUpdate.Destination.SESSION),
            new PermissionUpdate.SetMode(
                PermissionModeKind.ACCEPT_EDITS, PermissionUpdate.Destination.SESSION));
        PermissionAskContext context = PermissionAskContext.builder("Bash", null)
            .toolUseId("tool-1")
            .decisionReason("mode", "DEFAULT")
            .blockedPath("/private/tmp/cc197-tty-permission-approve-marker")
            .suggestions(suggestions)
            .build();

        dialog.show(PermissionPreviewPreparer.standard().prepare(context),
            null, applied::set, _ -> {}, () -> {});

        assertEquals("2. Yes, and always allow access to tmp/ from this project",
            dialog.allowSuggestionLabelForTest());
        dialog.resolveSuggestionForTest();
        assertEquals(suggestions, applied.get());
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }
}
