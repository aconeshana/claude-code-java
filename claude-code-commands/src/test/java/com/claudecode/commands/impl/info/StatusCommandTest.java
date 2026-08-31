package com.claudecode.commands.impl.info;


import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.StatusProperty;
import com.claudecode.commands.testing.FakeSettingsManagementPort;
import com.claudecode.core.message.Usage;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link StatusCommand}'s plain-text fallback, the shared
 * {@link StatusCommand#buildProperties} property list ({@link StatusPane} in
 * claude-code-ui reuses this), and the {@code statusDialogLauncher} hand-off.
 */
class StatusCommandTest {

    private static CommandContext minimalWithUsage() {
        CommandContext base = CommandContext.minimal();
        return CommandContext.builder(
            base.session().model(), base.session().messagesSupplier(), base.session().clearMessages(), base.session().setModel(),
            () -> new Usage(100, 50, 0, 0), _ -> 0.0021, base.session().workingDirectory(), base.session().remoteMode())
            .build();
    }

    @Test
    void execute_withoutDialogLauncher_returnsTextListing() {
        CommandResult r = new StatusCommand().execute(minimalWithUsage(), "");
        assertTrue(Strings.CS.startsWith(r.output(), "Session Status"));
        assertTrue(Strings.CS.contains(r.output(),
            "Model: " + minimalWithUsage().session().model()));
        assertFalse(Strings.CS.contains(r.output(), "Input tokens:"),
            "TS keeps token/cost information in /cost, not /status");
    }

    @Test
    void execute_withDialogLauncher_invokesItAndSkips() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        CommandContext ctx = ctxWithStatusDialogLauncher(() -> invoked.set(true));
        CommandResult r = new StatusCommand().execute(ctx, "");
        assertTrue(invoked.get());
        assertTrue(r.silent(), "dialog path must not also print the text listing");
    }

    @Test
    void buildProperties_includesCoreFields() {
        List<StatusProperty> props = StatusCommand.buildProperties(minimalWithUsage());
        assertTrue(props.stream().anyMatch(p -> Strings.CS.equals(p.label(), "Version")));
        assertTrue(props.stream().anyMatch(p -> Strings.CS.equals(p.label(), "Session name")));
        assertTrue(props.stream().anyMatch(p -> Strings.CS.equals(p.label(), "Model")));
        assertTrue(props.stream().anyMatch(p -> Strings.CS.equals(p.label(), "cwd")));
        assertFalse(props.stream().anyMatch(p -> Strings.CS.equals("Remote mode", p.label())));
        assertFalse(props.stream().anyMatch(p -> Strings.CS.equals("Messages in session", p.label())));
        assertFalse(props.stream().anyMatch(p -> Strings.CS.equals("Last activity", p.label())));
    }

    @Test
    void buildProperties_excludesCostCommandFieldsEvenWhenUsageExists() {
        List<StatusProperty> props = StatusCommand.buildProperties(minimalWithUsage());
        assertFalse(props.stream().anyMatch(p -> Strings.CS.equals(p.label(), "Input tokens")));
        assertFalse(props.stream().anyMatch(p -> Strings.CS.equals(p.label(), "Estimated cost")));
    }

    @Test
    void buildProperties_sessionNameFallsBackWhenNoCustomTitle() {
// CommandContext.minimal has currentSessionId == null (no shorter
        // overload sets it) — no session file to read a title from either way.
        List<StatusProperty> props = StatusCommand.buildProperties(CommandContext.minimal());
        assertTrue(props.stream().anyMatch(p ->
            Strings.CS.equals(p.label(), "Session name") && Strings.CS.equals(p.value(), "/rename to add a name")));
    }

    @Test
    void buildProperties_omitsSessionIdWhenSupplierMissing() {
        List<StatusProperty> props = StatusCommand.buildProperties(CommandContext.minimal());
        assertFalse(props.stream().anyMatch(p -> Strings.CS.equals(p.label(), "Session ID")));
    }

    @Test
    void buildProperties_omitsAnthropicBaseUrlWhenBlank() {
        List<StatusProperty> props = StatusCommand.buildProperties(CommandContext.minimal());
        assertFalse(props.stream().anyMatch(p -> Strings.CS.equals(p.label(), "Anthropic base URL")));
    }

    @Test
    void buildProperties_includesAnthropicBaseUrlWhenSupplied() {
        CommandContext ctx = ctxWithApiBaseUrl(() -> "https://example.com/anthropic/");
        List<StatusProperty> props = StatusCommand.buildProperties(ctx);
        assertTrue(props.stream().anyMatch(p ->
            Strings.CS.equals(p.label(), "Anthropic base URL") && Strings.CS.equals(p.value(), "https://example.com/anthropic/")));
    }

    @Test
    void buildProperties_insertsResolvedRuntimePropertiesAfterCwd() {
        List<StatusProperty> runtime = List.of(
            new StatusProperty("API key", "--api-key"),
            new StatusProperty("API provider", "AWS Bedrock"));
        CommandContext ctx = CommandContext.builder(
                "m", List::of, () -> {}, _ -> {}, () -> Usage.EMPTY,
                _ -> 0.0, ".", false)
            .statusRuntimePropertiesSupplier(() -> runtime)
            .build();

        List<StatusProperty> props = StatusCommand.buildProperties(ctx);
        int cwd = indexOf(props, "cwd");
        assertEquals("API key", props.get(cwd + 1).label());
        assertEquals("API provider", props.get(cwd + 2).label());
    }

    @Test
    void buildProperties_omitsMcpServersWhenSupplierMissingOrBlank() {
        List<StatusProperty> props = StatusCommand.buildProperties(CommandContext.minimal());
        assertFalse(props.stream().anyMatch(p -> Strings.CS.equals(p.label(), "MCP servers")));
    }

    @Test
    void buildProperties_includesMcpServersWhenSupplied() {
        CommandContext ctx = ctxWithMcpStatus(() -> "2 connected");
        List<StatusProperty> props = StatusCommand.buildProperties(ctx);
        assertTrue(props.stream().anyMatch(p ->
            Strings.CS.equals(p.label(), "MCP servers") && Strings.CS.equals(p.value(), "2 connected")));
    }

    @Test
    void buildProperties_settingSourcesListsExistingNonEmptyTiers(@TempDir Path tempDir) throws IOException {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.home", tempDir.toString());
            System.setProperty("user.dir", tempDir.toString());
            Files.createDirectories(tempDir.resolve(".claude"));
            Files.writeString(tempDir.resolve(".claude/settings.json"), "{\"alwaysThinkingEnabled\": true}");
            Files.writeString(tempDir.resolve(".claude/settings.local.json"), "{}"); // empty -> must not count

            CommandContext base = CommandContext.minimal();
            FakeSettingsManagementPort settings = new FakeSettingsManagementPort();
            settings.sourceLabels = List.of("User settings");
            CommandContext ctx = CommandContext.builder(
                base.session().model(), base.session().messagesSupplier(), base.session().clearMessages(), base.session().setModel(),
                base.session().usageSupplier(), _ -> 0.0, tempDir.toString(), base.session().remoteMode())
                .settingsManagement(settings).build();

            List<StatusProperty> props = StatusCommand.buildProperties(ctx);
            StatusProperty sources = props.stream().filter(p -> Strings.CS.equals(p.label(), "Setting sources"))
                .findFirst().orElseThrow();
            assertTrue(Strings.CS.contains(sources.value(), "User settings"), sources.value());
            assertFalse(Strings.CS.contains(sources.value(), "Local settings"), sources.value());
        } finally {
            System.setProperty("user.home", originalHome);
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void buildProperties_keepsEmptySettingSourcesRow(@TempDir Path tempDir) {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.home", tempDir.toString());
            System.setProperty("user.dir", tempDir.toString());

            CommandContext base = CommandContext.minimal();
            CommandContext ctx = CommandContext.builder(
                base.session().model(), base.session().messagesSupplier(), base.session().clearMessages(), base.session().setModel(),
                base.session().usageSupplier(), _ -> 0.0, tempDir.toString(), base.session().remoteMode())
                .build();

            List<StatusProperty> props = StatusCommand.buildProperties(ctx);
            StatusProperty row = props.stream()
                .filter(p -> Strings.CS.equals(p.label(), "Setting sources"))
                .findFirst().orElseThrow();
            assertEquals("", row.value());
        } finally {
            System.setProperty("user.home", originalHome);
            System.setProperty("user.dir", originalDir);
        }
    }

    private static CommandContext ctxWithStatusDialogLauncher(Runnable statusDialogLauncher) {
        return CommandContext.builder("m", List::of, () -> {}, _ -> {}, () -> Usage.EMPTY, _ -> 0.0, ".", false)
            .statusDialogLauncher(statusDialogLauncher)
            .build();
    }

    private static CommandContext ctxWithApiBaseUrl(Supplier<String> apiBaseUrlSupplier) {
        return CommandContext.builder("m", List::of, () -> {}, _ -> {}, () -> Usage.EMPTY, _ -> 0.0, ".", false)
            .apiBaseUrlSupplier(apiBaseUrlSupplier)
            .build();
    }

    private static CommandContext ctxWithMcpStatus(Supplier<String> mcpStatusSupplier) {
        return CommandContext.builder("m", List::of, () -> {}, _ -> {}, () -> Usage.EMPTY, _ -> 0.0, ".", false)
            .mcpStatusSupplier(mcpStatusSupplier)
            .build();
    }

    private static int indexOf(List<StatusProperty> properties, String label) {
        for (int i = 0; i < properties.size(); i++) {
            if (label.equals(properties.get(i).label())) return i;
        }
        return -1;
    }
}
