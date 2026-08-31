package com.claudecode.commands.impl.config;


import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;


class ModelCommandTest {

    /** Builds a full CommandContext wiring only the fields /model reads. */
    private static CommandContext ctx(String model, Consumer<String> setModel,
                                      Supplier<String> effortSupplier,
                                      Runnable modelLauncher,
                                      CommandContext.ModelApplyFromDialog applyFromDialog,
                                      Function<String, String> modelValidator) {
        return CommandContext.builder(
            model, List::of, () -> {}, setModel,
            null, _ -> 0.0, ".", false)
            .currentSessionId(() -> null)
            .effortValueSupplier(effortSupplier)
            .modelDialogLauncher(modelLauncher)
            .modelApplyFromDialog(applyFromDialog)
            .modelValidator(modelValidator)
            .build();
    }

    private static final ModelCommand CMD = new ModelCommand();

    @Test
    void infoArg_showsCurrentModel() {
        CommandResult r = CMD.execute(
            ctx("claude-sonnet-4-6", _ -> {}, () -> null, null, null, null), "current");
        assertEquals("Current model: Sonnet 4.6", r.output());
    }

    @Test
    void infoArg_includesEffortWhenSet() {
        CommandResult r = CMD.execute(
            ctx("claude-sonnet-4-6", _ -> {}, () -> "high", null, null, null), "show");
        assertEquals("Current model: Sonnet 4.6 (effort: high)", r.output());
    }

    @Test
    void helpArg_printsUsage() {
        CommandResult r = CMD.execute(
            ctx("claude-sonnet-4-6", _ -> {}, () -> null, null, null, null), "--help");
        assertEquals("Run /model to open the model selection menu, or /model [modelName] to set the model.",
            r.output());
    }

    @Test
    void empty_withLauncher_opensPickerAndSkips() {
        AtomicBoolean opened = new AtomicBoolean(false);
        CommandResult r = CMD.execute(
            ctx("claude-sonnet-4-6", _ -> {}, () -> null, () -> opened.set(true), null, null), "");
        assertTrue(opened.get());
        assertTrue(r.silent());
    }

    @Test
    void empty_noLauncher_showsCurrent() {
        CommandResult r = CMD.execute(
            ctx("claude-sonnet-4-6", _ -> {}, () -> null, null, null, null), "");
        assertEquals("Current model: Sonnet 4.6", r.output());
    }

    @Test
    void defaultArg_setsDefaultModel() {
        AtomicReference<String> set = new AtomicReference<>();
        CommandResult r = CMD.execute(
            ctx("claude-opus-4-8", set::set, () -> null, null, null, null), "default");
        // Not a hardcoded literal: ANTHROPIC_DEFAULT_SONNET_MODEL may be set on
        // the machine running this suite (env-independent, see ModelNames#defaultMainLoopModel).
        assertNull(set.get());
        assertTrue(Strings.CS.endsWith(r.output(), "(default)"));
    }

    @Test
    void alias_skipsLiveValidationButRunsAllowlistGate() {
        AtomicReference<String> set = new AtomicReference<>();
        AtomicBoolean validatorCalled = new AtomicBoolean(false);
        CommandResult r = CMD.execute(
            ctx("claude-sonnet-4-6", set::set, () -> null, null, null,
                _ -> { validatorCalled.set(true); return null; }), "opus");
        assertEquals("opus", set.get());
        assertTrue(validatorCalled.get(), "alias must pass through the allowlist gate");
        assertTrue(Strings.CS.startsWith(r.output(), "Set model to"));
    }

    @Test
    void aliasRejectedByAllowlistDoesNotSetModel() {
        AtomicReference<String> set = new AtomicReference<>();
        CommandResult r = CMD.execute(
            ctx("claude-sonnet-4-6", set::set, () -> null, null, null,
                _ -> "Model 'opus' is not available. Your organization restricts model selection."),
            "opus");
        assertNull(set.get());
        assertEquals("Model 'opus' is not available. Your organization restricts model selection.",
            r.output());
    }

    @Test
    void customValid_setsModel() {
        AtomicReference<String> set = new AtomicReference<>();
        CommandResult r = CMD.execute(
            ctx("claude-sonnet-4-6", set::set, () -> null, null, null, _ -> null), "claude-opus-4-8");
        assertEquals("claude-opus-4-8", set.get());
        assertEquals("Set model to Opus 4.8", r.output());
    }

    @Test
    void gpt56Alias_setsStableAliasWithReadableLabel() {
        AtomicReference<String> set = new AtomicReference<>();
        CommandResult r = CMD.execute(
            ctx("sonnet", set::set, () -> null, null, null, _ -> null), "sol");

        assertEquals("sol", set.get());
        assertEquals("Set model to GPT-5.6 Sol", r.output());
    }

    @Test
    void customInvalid_returnsErrorAndDoesNotSet() {
        AtomicReference<String> set = new AtomicReference<>();
        CommandResult r = CMD.execute(
            ctx("claude-sonnet-4-6", set::set, () -> null, null, null,
                _ -> "Model 'bogus' not found"), "bogus");
        assertNull(set.get(), "invalid model must not be set");
        assertEquals("Model 'bogus' not found", r.output());
    }

    @Test
    void applyFromDialog_setsModelAndEffortSuffix() {
        AtomicReference<String> set = new AtomicReference<>();
        CommandResult r = CMD.applyFromDialog(
            ctx("claude-sonnet-4-6", set::set, () -> null, null, null, _ -> null), "claude-opus-4-8", "high");
        assertEquals("claude-opus-4-8", set.get());
        assertEquals("Set model to Opus 4.8 with high effort", r.output());
    }

    @Test
    void applyFromDialog_doesNotProbeKnownPickerSelectionOverNetwork() {
        AtomicReference<String> set = new AtomicReference<>();
        AtomicBoolean validatorCalled = new AtomicBoolean(false);

        CommandResult r = CMD.applyFromDialog(
            ctx("claude-sonnet-4-6", set::set, () -> null, null, null, _ -> {
                validatorCalled.set(true);
                return "Authentication failed. Please check your API credentials.";
            }), "claude-opus-4-8", null);

        assertFalse(validatorCalled.get(),
            "the picker already presents curated options; Enter must be local-only like released 2.1.197");
        assertEquals("claude-opus-4-8", set.get());
        assertEquals("Set model to Opus 4.8", r.output());
    }

    @Test
    void applyFromDialog_nullModelIsDefault_noEffortSuffix() {
        AtomicReference<String> set = new AtomicReference<>();
        CommandResult r = CMD.applyFromDialog(
            ctx("claude-opus-4-8", set::set, () -> null, null, null, null), null, null);
        assertNull(set.get());
        assertTrue(Strings.CS.endsWith(r.output(), "(default)"));
        assertFalse(Strings.CS.contains(r.output(), "effort"));
    }
}
