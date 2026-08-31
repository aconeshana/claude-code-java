package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.runtime.sessionhost.CollaborationSetupPort;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class FeishuSetupDialogTest {

    @Test
    void activeDialogRendersTheSetupChoices() {
        FeishuSetupDialog dialog = new FeishuSetupDialog();
        dialog.show(new FakeSetup(), () -> {});
        TerminalSize size = dialog.calculatePreferredSize();
        dialog.setSize(size);
        BasicTextImage image = new BasicTextImage(size);

        dialog.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));

        String rendered = renderedText(image);
        assertTrue(Strings.CS.contains(rendered, "Set up Feishu collaboration"));
        assertTrue(Strings.CS.contains(rendered, "Create a new bot (scan QR)"));
        assertTrue(Strings.CS.contains(rendered, "Bind an existing Feishu app"));
        assertTrue(Strings.CS.contains(rendered, "Enter to select · Esc to cancel"));
    }

    @Test
    void setupChoiceUsesReleasedSelectNavigationAndNumericSelection() {
        FeishuSetupDialog vim = new FeishuSetupDialog();
        vim.show(new FakeSetup(), () -> {});
        route(vim, new KeyStroke('j', false, false));
        key(vim, KeyType.ENTER);
        text(vim, "app-from-j");
        assertEquals("app-from-j", vim.renderedInputForTest());

        FeishuSetupDialog ctrl = new FeishuSetupDialog();
        ctrl.show(new FakeSetup(), () -> {});
        route(ctrl, new KeyStroke('n', true, false));
        key(ctrl, KeyType.ENTER);
        text(ctrl, "app-from-ctrl-n");
        assertEquals("app-from-ctrl-n", ctrl.renderedInputForTest());

        FeishuSetupDialog numeric = new FeishuSetupDialog();
        numeric.show(new FakeSetup(), () -> {});
        route(numeric, new KeyStroke('２', false, false));
        text(numeric, "app-from-two");
        assertEquals("app-from-two", numeric.renderedInputForTest());
    }

    @Test
    void ctrlCAndCtrlDUseTheSharedDoublePressExitGate() {
        FeishuSetupDialog dialog = new FeishuSetupDialog();
        AtomicReference<Character> exit = new AtomicReference<>();
        dialog.setExitGestureHandler(exit::set);
        dialog.show(new FakeSetup(), () -> {});

        route(dialog, new KeyStroke('c', true, false));

        assertEquals('c', exit.get());
        assertTrue(dialog.isActive());
        TerminalSize size = dialog.calculatePreferredSize();
        dialog.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        dialog.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        assertTrue(Strings.CS.contains(renderedText(image), "Press Ctrl-C again to exit"));
    }

    @Test
    void masksExistingAppSecretBeforeSubmission() {
        FeishuSetupDialog dialog = new FeishuSetupDialog();
        dialog.show(new FakeSetup(), () -> {});
        key(dialog, KeyType.ARROW_DOWN);
        key(dialog, KeyType.ENTER);
        text(dialog, "cli_test");
        key(dialog, KeyType.ENTER);
        text(dialog, "super-secret");

        assertEquals("•".repeat(12), dialog.renderedInputForTest());
    }

    @Test
    void pastedCredentialsStayInsideTheSetupDialog() {
        FeishuSetupDialog dialog = new FeishuSetupDialog();
        dialog.show(new FakeSetup(), () -> {});
        key(dialog, KeyType.ARROW_DOWN);
        key(dialog, KeyType.ENTER);

        AtomicBoolean appIdDeliver = new AtomicBoolean(true);
        dialog.handleKey(new PasteKeyStroke("cli_test\n"), appIdDeliver);
        assertEquals("cli_test", dialog.renderedInputForTest());
        assertFalse(appIdDeliver.get());
        key(dialog, KeyType.ENTER);

        AtomicBoolean secretDeliver = new AtomicBoolean(true);
        dialog.handleKey(new PasteKeyStroke("super-secret\r\n"), secretDeliver);
        assertEquals("•".repeat(12), dialog.renderedInputForTest());
        assertFalse(secretDeliver.get());
    }

    @Test
    void activeCredentialDialogConsumesUnsupportedKeys() {
        FeishuSetupDialog dialog = new FeishuSetupDialog();
        dialog.show(new FakeSetup(), () -> {});
        key(dialog, KeyType.ARROW_DOWN);
        key(dialog, KeyType.ENTER);
        AtomicBoolean deliver = new AtomicBoolean(true);

        dialog.handleKey(new KeyStroke(KeyType.TAB), deliver);

        assertFalse(deliver.get());
        assertEquals("", dialog.renderedInputForTest());
    }

    @Test
    void pendingSetupContinuesTargetDiscoveryWithoutRequestingCredentialsAgain() {
        FakeSetup setup = new FakeSetup(true);
        FeishuSetupDialog dialog = new FeishuSetupDialog();

        dialog.show(setup, () -> {});

        assertEquals(CollaborationSetupPort.Mode.RESUME, setup.request.get().mode());
        assertEquals(28, dialog.calculatePreferredSize().getRows());
    }

    @Test
    void narrowRunningDialogWrapsProgressAndFooterInsteadOfCroppingThem() {
        FakeSetup setup = new FakeSetup(false,
            "Open the Feishu developer console and finish the bot configuration.");
        FeishuSetupDialog dialog = new FeishuSetupDialog();
        dialog.show(setup, () -> {});
        key(dialog, KeyType.ENTER);
        TerminalSize size = new TerminalSize(40, 13);
        dialog.setSize(size);
        BasicTextImage image = new BasicTextImage(size);

        dialog.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));

        String rendered = renderedText(image);
        assertTrue(Strings.CS.contains(rendered, "Open the Feishu developer console"));
        assertTrue(Strings.CS.contains(rendered, "and finish the bot configuration."));
        assertTrue(Strings.CS.contains(rendered, "Follow the instructions above · Esc"));
        assertTrue(Strings.CS.contains(rendered, "to cancel"));
    }

    private static void text(FeishuSetupDialog dialog, String value) {
        value.chars().forEach(ch -> route(dialog, new KeyStroke((char) ch, false, false)));
    }

    private static void key(FeishuSetupDialog dialog, KeyType type) {
        route(dialog, new KeyStroke(type));
    }

    private static void route(FeishuSetupDialog dialog, KeyStroke key) {
        AtomicBoolean deliver = new AtomicBoolean(true);
        dialog.handleKey(key, deliver);
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

    private static final class FakeSetup implements CollaborationSetupPort {
        private final boolean pending;
        private final String progressLine;
        private final AtomicReference<Request> request = new AtomicReference<>();

        private FakeSetup() { this(false); }
        private FakeSetup(boolean pending) { this(pending, null); }
        private FakeSetup(boolean pending, String progressLine) {
            this.pending = pending;
            this.progressLine = progressLine;
        }

        @Override public boolean configured() { return false; }
        @Override public boolean setupPending() { return pending; }
        @Override public CompletableFuture<Result> setup(Request request, Consumer<String> progress) {
            this.request.set(request);
            if (progressLine != null) progress.accept(progressLine);
            return new CompletableFuture<>();
        }
        @Override public void cancel() {}
    }
}
