package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.dialog.ModelPickerDialog.ModelPickResult;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.function.Consumer;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.model.ModelCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-machine tests for {@link ModelPickerDialog}, driven directly (no real
 * GUI thread) — same pattern as {@code ThemePickerDialogTest}.
 *
 * <p>Every test uses the package-private {@code show(..., envLookup)} overload
 * with an empty lookup ({@code key -> null}) so results never depend on
 * {@code ANTHROPIC_DEFAULT_*_MODEL} actually being set on the machine running
 * the suite — with the empty lookup, options come from the bundled catalogue:
 * {@code [0]=Default, [1]=fable, [2]=opus, [3]=sonnet, [4]=haiku}; their concrete IDs
 * are resolved by the centralized model catalogue. Sonnet 5 supports effort,
 * while Haiku does not, so effort-toggle tests use Sonnet.
 */
class ModelPickerDialogTest {

    private static KeyStroke k(KeyType t) { return new KeyStroke(t); }

/** Deterministic show — empty env, independent of the real process environment. */
    private static void show(ModelPickerDialog d, String currentModel, String currentEffort,
                             Consumer<ModelPickResult> onResult) {
        d.show(currentModel, currentEffort, onResult, _ -> null);
    }

    @Test
    void idle_hasZeroPreferredSize() {
        ModelPickerDialog d = new ModelPickerDialog();
        assertFalse(d.isActive());
        assertEquals(new TerminalSize(0, 0), d.calculatePreferredSize());
    }

    @Test
    void show_activatesAndPreselectsCurrentModel() {
        AtomicReference<ModelPickerDialog.ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "claude-sonnet-5", null, result::set);
        assertTrue(d.isActive());
        // Focus is on Sonnet (index 3); Enter without touching effort returns it.
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertFalse(d.isActive());
        assertEquals("sonnet", result.get().model());
    }

    @Test
    void aliasCurrentModelDoesNotAppendASecondFamilyRow() {
        AtomicReference<ModelPickerDialog.ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "sonnet", null, result::set);

        assertEquals(12, d.calculatePreferredSize().getRows(),
            "Default + four model families; alias must not create a duplicate current row");
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("sonnet", result.get().model());
    }

    @Test
    void resolvedCurrentModelSelectsItsAliasRowWithoutDuplication() {
        AtomicReference<ModelPickerDialog.ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "claude-sonnet-5", null, result::set);

        assertEquals(12, d.calculatePreferredSize().getRows());
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("sonnet", result.get().model());
    }

    @Test
    void firstPartyFableModelSelectsTheStableAliasWithoutDuplication() {
        AtomicReference<ModelPickerDialog.ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "claude-fable-5", null, result::set);

        assertEquals(12, d.calculatePreferredSize().getRows());
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("fable", result.get().model());
    }

    @Test
    void preparedConfirmDoesNotReReadPolicyOrCustomModelSources() {
        AtomicInteger allowlistReads = new AtomicInteger();
        AtomicReference<ModelPickerDialog.ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        d.setModelAllowed(_ -> {
            allowlistReads.incrementAndGet();
            return true;
        });
        ModelPickerDialog.PreparedModelPicker prepared = d.prepare(
            "claude-sonnet-5", null, null, List.of());
        int readsAfterPrepare = allowlistReads.get();

        d.setModelAllowed(_ -> {
            throw new AssertionError("Enter must not re-read the settings-backed allowlist");
        });
        d.setCustomModelsSupplier(() -> {
            throw new AssertionError("Enter must not reload the custom-model catalogue");
        });
        d.show(prepared, result::set);
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals(readsAfterPrepare, allowlistReads.get());
        assertEquals("sonnet", result.get().model());
    }

    @Test
    void arrowDown_navigatesToNextModel() {
        AtomicReference<ModelPickerDialog.ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "sonnet", null, result::set); // idx 3
        d.handleKey(k(KeyType.ARROW_DOWN), new AtomicBoolean(true)); // idx 4 = Haiku
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("haiku", result.get().model());
    }

    @Test
    void productionArrowQueuesAuxiliaryEffortRefresh() {
        ModelPickerDialog d = new ModelPickerDialog();
        List<Runnable> guiTasks = new ArrayList<>();
        d.setGuiInvoker(guiTasks::add);
        show(d, "sonnet", null, _ -> {});

        d.handleKey(k(KeyType.ARROW_DOWN), new AtomicBoolean(true));

        assertEquals(1, guiTasks.size(),
            "selection pointer paints before the auxiliary effort row");
        guiTasks.removeFirst().run();
    }

    @Test
    void productionArrowSkipsAuxiliaryEffortRefreshWhenVisualIsUnchanged() {
        ModelPickerDialog d = new ModelPickerDialog();
        List<Runnable> guiTasks = new ArrayList<>();
        d.setGuiInvoker(guiTasks::add);
        show(d, null, null, _ -> {});

        d.handleKey(k(KeyType.ARROW_DOWN), new AtomicBoolean(true));

        assertTrue(guiTasks.isEmpty(),
            "an identical effort row must not enqueue a second GUI frame");
    }

    @Test
    void defaultOption_returnsNullModel() {
        AtomicReference<ModelPickerDialog.ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "sonnet", null, result::set); // idx 3
        // Up three times: 3 -> 2 -> 1 -> 0 (Default).
        d.handleKey(k(KeyType.ARROW_UP), new AtomicBoolean(true));
        d.handleKey(k(KeyType.ARROW_UP), new AtomicBoolean(true));
        d.handleKey(k(KeyType.ARROW_UP), new AtomicBoolean(true));
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertNull(result.get().model(), "Default (recommended) option → null model");
    }

    @Test
    void effortToggle_thenEnter_returnsToggledEffort() {
        AtomicReference<ModelPickerDialog.ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "sonnet", null, result::set); // Sonnet supports effort
        // Right from default "high" → "xhigh"; toggled=true makes
        // persistence deterministic regardless of on-disk settings.
        d.handleKey(k(KeyType.ARROW_RIGHT), new AtomicBoolean(true));
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("sonnet", result.get().model());
        assertEquals("xhigh", result.get().effort());
    }

    @Test
    void effortToggle_onNonEffortModel_isNoOp() {
        AtomicReference<ModelPickerDialog.ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "sonnet", null, result::set);
        d.handleKey(k(KeyType.ARROW_DOWN), new AtomicBoolean(true)); // idx 4 = Haiku (no effort)
        d.handleKey(k(KeyType.ARROW_RIGHT), new AtomicBoolean(true)); // no-op
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("haiku", result.get().model());
        assertNull(result.get().effort(), "Haiku has no effort → set model alone");
    }

    @Test
    void escape_cancelsWithNullResult() {
        AtomicReference<ModelPickerDialog.ModelPickResult> result = new AtomicReference<>();
        AtomicBoolean called = new AtomicBoolean(false);
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "sonnet", null, r -> { called.set(true); result.set(r); });
        d.handleKey(k(KeyType.ESCAPE), new AtomicBoolean(true));
        assertFalse(d.isActive());
        assertTrue(called.get());
        assertNull(result.get());
    }

    @Test
    void footerFitsWithinPreferredHeight() {
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "sonnet", null, _ -> {});
        // 5 options → height = OPTIONS_START(3) + 5 + 4 = 12; footer remains in bounds.
        // Regression guard for the previous off-by-one where the footer clipped.
        assertEquals(12, d.calculatePreferredSize().getRows());
    }

    @Test
    void parentPanelDoesNotClearAreaAlreadyPaintedByPicker() {
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "sonnet", null, _ -> {});
        TerminalSize size = d.calculatePreferredSize();
        d.setSize(size);
        AtomicInteger parentFills = new AtomicInteger();
        TextGUIGraphics backing = TextGUIGraphicsBridge.wrap(
            null, new BasicTextImage(size).newTextGraphics());
        TextGUIGraphics observed = (TextGUIGraphics) Proxy.newProxyInstance(
            TextGUIGraphics.class.getClassLoader(),
            new Class<?>[]{TextGUIGraphics.class},
            (_, method, args) -> {
                if (Strings.CS.equals("fill", method.getName())) parentFills.incrementAndGet();
                return method.invoke(backing, args);
            });

        d.draw(observed);

        assertEquals(0, parentFills.get(),
            "PickerArea owns the whole panel and performs the only required clear");
    }

    @Test
    void arrowMoveRedrawsOnlyPointersAndEffortRow() {
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "sonnet", null, _ -> {});
        TerminalSize size = d.calculatePreferredSize();
        d.setSize(size);
        TextGUIGraphics backing = TextGUIGraphicsBridge.wrap(
            null, new BasicTextImage(size).newTextGraphics());
        d.draw(backing);
        AtomicInteger strings = new AtomicInteger();
        AtomicInteger fullFills = new AtomicInteger();
        TextGUIGraphics observed = (TextGUIGraphics) Proxy.newProxyInstance(
            TextGUIGraphics.class.getClassLoader(),
            new Class<?>[]{TextGUIGraphics.class},
            (_, method, args) -> {
                if (Strings.CS.equals("putString", method.getName())) strings.incrementAndGet();
                if (Strings.CS.equals("fill", method.getName())) fullFills.incrementAndGet();
                return method.invoke(backing, args);
            });

        d.handleKey(k(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        d.draw(observed);

        assertEquals(0, fullFills.get(), "stable navigation must not clear the picker");
        assertTrue(strings.get() <= 6,
            "one arrow move should paint two pointers plus the effort row, not every option");
    }

    @Test
    void arrowMoveSkipsAnEffortRowWhoseVisualContentIsUnchanged() {
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, null, null, _ -> {});
        TerminalSize size = d.calculatePreferredSize();
        d.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        TextGUIGraphics backing = TextGUIGraphicsBridge.wrap(null, image.newTextGraphics());
        d.draw(backing);
        List<String> before = imageRows(image);

        d.handleKey(k(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        d.draw(backing);
        List<String> after = imageRows(image);

        int changedRows = 0;
        for (int row = 0; row < before.size(); row++) {
            if (!before.get(row).equals(after.get(row))) changedRows++;
        }
        assertEquals(2, changedRows,
            "only the old and new pointer rows change when effort text is identical");
    }

    private static List<String> imageRows(BasicTextImage image) {
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < image.getSize().getRows(); row++) {
            StringBuilder line = new StringBuilder();
            for (int column = 0; column < image.getSize().getColumns(); column++) {
                line.append(image.getCharacterAt(column, row).getCharacterString());
            }
            rows.add(line.toString());
        }
        return rows;
    }

    @Test
    void consumesNavigationKeys() {
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "sonnet", null, _ -> {});
        AtomicBoolean deliver = new AtomicBoolean(true);
        d.handleKey(k(KeyType.ARROW_DOWN), deliver);
        assertFalse(deliver.get(), "picker must swallow navigation keys");
    }

    @Test
    void idle_afterHide_hasZeroPreferredSizeAndDoesNotThrow() {
// Regression guard: PickerRenderer.getPreferredSize is queried by the
// Lanterna layout manager even while inactive (before the first show
        // and again after Esc/Enter hides it) — must not NPE on a null options list.
        ModelPickerDialog d = new ModelPickerDialog();
        assertEquals(new TerminalSize(0, 0), d.calculatePreferredSize());
        show(d, "sonnet", null, _ -> {});
        d.handleKey(k(KeyType.ESCAPE), new AtomicBoolean(true));
        assertFalse(d.isActive());
        assertEquals(new TerminalSize(0, 0), d.calculatePreferredSize());
    }

    @Test
    void envOverride_replacesOptionAndIsSelectable() {
// Full show path with an injected non-empty lookup — the picker's real
        // consumer path (the machine's actual env is never touched).
        AtomicReference<ModelPickerDialog.ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        d.show("anthropic.claude-opus-4-8", null, result::set,
            key -> Strings.CS.equals("ANTHROPIC_DEFAULT_OPUS_MODEL", key) ? "anthropic.claude-opus-4-8" : null);
        // idx 2 = Opus, now carrying the env override value — currentModel matches it,

        // duplicate-row behavior — see ModelPickerDialog's class Javadoc).
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("opus", result.get().model());
        // Dedup: only 5 rows (no appended duplicate), so height stays at 12.
        d.show("anthropic.claude-opus-4-8", null, _ -> {},
            key -> Strings.CS.equals("ANTHROPIC_DEFAULT_OPUS_MODEL", key) ? "anthropic.claude-opus-4-8" : null);
        assertEquals(12, d.calculatePreferredSize().getRows());
    }

    @Test
    void unknownCurrentModel_isAppendedAndMarkedCurrent() {
        AtomicReference<ModelPickerDialog.ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        show(d, "some-custom-model-not-in-list", null, result::set);
        // Appended after Default + four families and immediately focused on open.
        assertEquals(13, d.calculatePreferredSize().getRows());
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("some-custom-model-not-in-list", result.get().model());
    }

    @Test
    void organizationAllowlist_filtersOptionsButKeepsDefault() {
        ModelPickerDialog d = new ModelPickerDialog();
        d.setModelAllowed("sonnet"::equals);
        show(d, "sonnet", null, _ -> {});
        // Default is always available; the only configured model option is Sonnet.
        assertEquals(9, d.calculatePreferredSize().getRows());
    }

    @Test
    void organizationAllowlist_keepsDisallowedCurrentModelVisible() {
        AtomicReference<ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        d.setModelAllowed("sonnet"::equals);
        show(d, "claude-opus-4-8", null, result::set);


        // session that predates a narrower policy remains representable.
        assertEquals(10, d.calculatePreferredSize().getRows());
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("claude-opus-4-8", result.get().model());
    }

    @Test
    void modelPickerAndSelectContextsSupportCustomBindingsAndNullUnbind(
            @TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"ModelPicker","bindings":{
                "x":"modelPicker:increaseEffort",
                "right":null
              }},
              {"context":"Select","bindings":{
                "z":"select:accept",
                "enter":null
              }}
            ]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            AtomicReference<ModelPickResult> result = new AtomicReference<>();
            ModelPickerDialog d = new ModelPickerDialog();
            d.setKeybindingsStore(store);
            show(d, "sonnet", null, result::set);

            d.handleKey(k(KeyType.ARROW_RIGHT), new AtomicBoolean(true));
            d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
            assertTrue(d.isActive(), "null-unbound default keys must be consumed");
            assertNull(result.get());

            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            d.handleKey(new KeyStroke('z', false, false), new AtomicBoolean(true));
            assertFalse(d.isActive());
            assertEquals("xhigh", result.get().effort());
        } finally {
            store.dispose();
        }
    }

    @Test
    void customModelsAreListedAndAddCustomModelIsAMenuAction() {
        AtomicReference<ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        d.setCustomModelsSupplier(() -> List.of(new CustomModelConfig(
            "gpt-custom", ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", "secret", Map.of())));
        show(d, "gpt-custom", null, result::set);

        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("gpt-custom", result.get().model());

        result.set(null);
        show(d, "gpt-custom", null, result::set);
        d.handleKey(k(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertTrue(result.get().addCustomModel());
    }

    @Test
    void customModelDeleteRequiresConfirmationAndKeepsThePickerOpen() {
        AtomicReference<ModelPickResult> result = new AtomicReference<>();
        AtomicReference<String> deleted = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        d.setCustomModelsSupplier(() -> List.of(
            customModel("alpha-custom"), customModel("beta-custom")));
        d.setCustomModelDeleteHandler(name -> {
            deleted.set(name);
            return CompletableFuture.completedFuture(null);
        });
        show(d, "alpha-custom", null, result::set);
        TerminalSize size = d.calculatePreferredSize();
        d.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        TextGUIGraphics graphics = TextGUIGraphicsBridge.wrap(null, image.newTextGraphics());

        d.handleKey(new KeyStroke('X', false, false), new AtomicBoolean(true));
        d.draw(graphics);

        String confirmation = String.join("\n", imageRows(image));
        assertTrue(Strings.CS.contains(confirmation, "Delete custom model?"));
        assertTrue(Strings.CS.contains(confirmation, "alpha-custom"));
        assertTrue(Strings.CS.contains(confirmation, "switch to Default"));
        assertNull(deleted.get(), "x only opens confirmation");

        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        d.draw(graphics);

        assertEquals("alpha-custom", deleted.get());
        assertTrue(d.isActive(), "successful deletion keeps the picker open");
        String refreshed = String.join("\n", imageRows(image));
        assertFalse(Strings.CS.contains(refreshed, "alpha-custom"));
        assertTrue(Strings.CS.contains(refreshed, "beta-custom"));
        assertTrue(Strings.CS.contains(refreshed, "Default (recommended) ✓"));

        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("beta-custom", result.get().model(),
            "the row following the deleted item retains focus");
    }

    @Test
    void customModelDeleteCanBeCancelledWithoutCallingTheHandler() {
        AtomicInteger deletes = new AtomicInteger();
        AtomicReference<ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        d.setCustomModelsSupplier(() -> List.of(customModel("cancel-custom")));
        d.setCustomModelDeleteHandler(_ -> {
            deletes.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        show(d, "cancel-custom", null, result::set);

        d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
        d.handleKey(k(KeyType.ESCAPE), new AtomicBoolean(true));
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));

        assertEquals(0, deletes.get());
        assertEquals("cancel-custom", result.get().model());
    }

    @Test
    void deleteShortcutIgnoresBuiltInAndAddCustomRows() {
        AtomicInteger deletes = new AtomicInteger();
        ModelPickerDialog d = new ModelPickerDialog();
        d.setCustomModelsSupplier(() -> List.of(customModel("only-custom")));
        d.setCustomModelDeleteHandler(_ -> {
            deletes.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        show(d, "sonnet", null, _ -> {});

        d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
        d.handleKey(k(KeyType.ARROW_DOWN), new AtomicBoolean(true)); // Haiku
        d.handleKey(k(KeyType.ARROW_DOWN), new AtomicBoolean(true)); // custom
        d.handleKey(k(KeyType.ARROW_DOWN), new AtomicBoolean(true)); // Add custom model
        d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));

        assertEquals(0, deletes.get());
        d.handleKey(k(KeyType.ESCAPE), new AtomicBoolean(true));
        assertFalse(d.isActive(), "x on non-deletable rows must not enter confirmation");
    }

    @Test
    void failedDeleteStaysInConfirmationAndDoesNotExposeTheFailureMessage() {
        AtomicInteger attempts = new AtomicInteger();
        ModelPickerDialog d = new ModelPickerDialog();
        d.setCustomModelsSupplier(() -> List.of(customModel("retry-custom")));
        d.setCustomModelDeleteHandler(_ -> attempts.incrementAndGet() == 1
            ? CompletableFuture.failedFuture(
                new IllegalStateException("api-key-secret and X-Tenant private"))
            : CompletableFuture.completedFuture(null));
        show(d, "retry-custom", null, _ -> {});
        TerminalSize size = d.calculatePreferredSize();
        d.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        TextGUIGraphics graphics = TextGUIGraphicsBridge.wrap(null, image.newTextGraphics());

        d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        d.draw(graphics);

        String failure = String.join("\n", imageRows(image));
        assertTrue(Strings.CS.contains(failure, "Could not delete custom model"));
        assertFalse(Strings.CS.contains(failure, "api-key-secret"));
        assertFalse(Strings.CS.contains(failure, "X-Tenant"));
        assertTrue(d.isActive());

        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals(2, attempts.get());
        assertTrue(d.isActive());
    }

    @Test
    void customDeleteBindingCanBeOverriddenOrExplicitlyUnbound(@TempDir Path tmp)
            throws Exception {
        Path overrideFile = tmp.resolve("override-keybindings.json");
        Files.writeString(overrideFile, """
            [{"context":"ModelPicker","bindings":{"x":"select:accept"}}]
            """);
        UserKeybindingsStore overrideStore = createStore(overrideFile);
        try {
            AtomicInteger deletes = new AtomicInteger();
            AtomicReference<ModelPickResult> result = new AtomicReference<>();
            ModelPickerDialog d = new ModelPickerDialog();
            d.setKeybindingsStore(overrideStore);
            d.setCustomModelsSupplier(() -> List.of(customModel("bound-custom")));
            d.setCustomModelDeleteHandler(_ -> {
                deletes.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            show(d, "bound-custom", null, result::set);

            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));

            assertEquals(0, deletes.get());
            assertEquals("bound-custom", result.get().model());
        } finally {
            overrideStore.dispose();
        }

        Path unboundFile = tmp.resolve("unbound-keybindings.json");
        Files.writeString(unboundFile, """
            [{"context":"ModelPicker","bindings":{"x":null}}]
            """);
        UserKeybindingsStore unboundStore = createStore(unboundFile);
        try {
            AtomicInteger deletes = new AtomicInteger();
            AtomicReference<ModelPickResult> result = new AtomicReference<>();
            ModelPickerDialog d = new ModelPickerDialog();
            d.setKeybindingsStore(unboundStore);
            d.setCustomModelsSupplier(() -> List.of(customModel("unbound-custom")));
            d.setCustomModelDeleteHandler(_ -> {
                deletes.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            show(d, "unbound-custom", null, result::set);

            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));

            assertEquals(0, deletes.get());
            assertEquals("unbound-custom", result.get().model());
        } finally {
            unboundStore.dispose();
        }
    }

    @Test
    void nonFirstPartyPickerDoesNotOfferUnconfiguredBuiltInFamiliesOrDefault() {
        AtomicReference<ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        d.setBuiltInFamiliesVisible(false);
        d.setCustomModelsSupplier(() -> List.of(new CustomModelConfig(
            "gateway-model", ModelApiProtocol.ANTHROPIC,
            "https://gateway.example/v1", "secret", Map.of())));

        show(d, null, null, result::set);

        // One configured custom model + Add custom model; no implicit Default/Claude rows.
        assertEquals(9, d.calculatePreferredSize().getRows());
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("gateway-model", result.get().model());
    }

    @Test
    void unavailableCurrentBuiltInIsNotReintroducedAsACurrentModelRow() {
        AtomicReference<ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        d.setBuiltInFamiliesVisible(false);
        d.setCustomModelsSupplier(List::of);

        show(d, ModelCatalog.LATEST_SONNET, null, result::set);

        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertTrue(result.get().addCustomModel());
    }

    @Test
    void unavailableOpusPlanPreferenceIsNotReintroducedWithoutBothMappedFamilies() {
        AtomicReference<ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        d.setBuiltInFamiliesVisible(false);
        d.setCustomModelsSupplier(List::of);

        show(d, "opusplan", null, result::set);

        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertTrue(result.get().addCustomModel());
    }

    @Test
    void nonFirstPartyPickerStillOffersFamiliesWithExplicitEnvironmentMappings() {
        AtomicReference<ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        d.setBuiltInFamiliesVisible(false);

        d.show(null, null, result::set, key ->
            Strings.CS.equals("ANTHROPIC_DEFAULT_SONNET_MODEL", key)
                ? "gateway-sonnet" : null);

        assertEquals(8, d.calculatePreferredSize().getRows());
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("sonnet", result.get().model());
    }

    @Test
    void settingsOverrideProviderIdSelectsAliasWithoutDuplicateCurrentRow() {
        AtomicReference<ModelPickResult> result = new AtomicReference<>();
        ModelCatalog.installModelOverrideLookup(modelId ->
            Strings.CS.equals("claude-sonnet-5", modelId) ? "provider-sonnet" : null);
        try {
            ModelPickerDialog d = new ModelPickerDialog();
            d.setBuiltInFamiliesVisible(false);

            show(d, "provider-sonnet", null, result::set);

            assertEquals(8, d.calculatePreferredSize().getRows());
            d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
            assertEquals("sonnet", result.get().model());
        } finally {
            ModelCatalog.installModelOverrideLookup(null);
        }
    }

    @Test
    void unknownCustomModelClearsInheritedEffortToAutoUntilUserToggles() {
        AtomicReference<ModelPickResult> result = new AtomicReference<>();
        ModelPickerDialog d = new ModelPickerDialog();
        CustomModelConfig custom = new CustomModelConfig(
            "gateway-alias", ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", "secret", Map.of());

        d.show("gateway-alias", "high", "high", List.of(custom), result::set);
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("auto", result.get().effort());

        result.set(null);
        d.show("gateway-alias", "high", "high", List.of(custom), result::set);
        d.handleKey(k(KeyType.ARROW_LEFT), new AtomicBoolean(true));
        d.handleKey(k(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals("medium", result.get().effort());
    }

    private static CustomModelConfig customModel(String name) {
        return new CustomModelConfig(name, ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", "secret", Map.of("X-Tenant", "private"));
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }
}
