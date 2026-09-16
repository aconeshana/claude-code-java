package com.claudecode.ui.lanterna.repl;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.impl.terminal.CopyCommand;
import com.claudecode.commands.session.ResumeRequest;
import com.claudecode.core.engine.CompactProgressEvent;
import com.claudecode.core.pokemon.PokemonProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Late-bound command-to-UI capability bridge.
 *
 * <p>The CLI composition root binds slash-command launchers to the public methods of this class
 * before the screen exists; the screen installs the concrete feature objects once the scene is
 * built. Every public method is a null-safe no-op until {@link #install} runs, which matches the
 * "screen not ready yet" semantics command launchers previously relied on.
 */
public final class ReplCommandUiBridge {

    public interface Preferences {
        void openEffort();
        void openModel();
        void showEffortNotification(String value);
        void openTheme(String current);
        void openConfig();
        void openStatus();
        void openUsage();
    }

    public interface Permissions {
        void openRules();
        void openAddDirectory(String path);
    }

    public interface Agents {
        void openAgents();
    }

    public interface Sandbox {
        void openSandbox();
    }

    public interface Memory {
        void openMemoryDialog();
        void openFileInEditor(Path file);
    }

    public interface Session {
        void clearConversation();
        void switchActiveSession(String id);
        void resetSessionCost();
        void resume(ResumeRequest request);
        void openMessageSelector();
    }

    /** {@code /help}, {@code /diff}, {@code /export}, {@code /copy}, {@code /context}, tag removal. */
    public interface Conversation {
        void openHelp();
        void openDiff();
        void openExport(String content);
        void openCopyPicker(String fullText, List<CopyCommand.CodeBlock> codeBlocks, boolean skipPicker);
        void showContextVisualization();
        void openTagRemoval(CommandContext.TagRemovalRequest request);
    }

    /** {@code /doctor}, {@code /stats}, {@code /skills}. */
    public interface Diagnostics {
        void openDoctor();
        void openStats();
        void openSkills();
    }

    public interface Hooks {
        void openHooks();
    }

    public interface Mcp {
        void openMcp();
    }

    /** {@code /tasks} and {@code /workflows}. */
    public interface Tasks {
        void openTasks();
        void openWorkflows();
        /** Opens the workflows browser focused on {@code taskId}, optionally returning to /tasks on close. */
        void openWorkflows(String taskId, boolean returnToTasks);
    }

    public interface Plugins {
        void openPluginPanel(String args);
    }

    public interface Goal {
        void openGoal();
    }

    public interface Compact {
        void progress(CompactProgressEvent event);
    }

    public interface Pokemon {
        void setWelcomePokemon(PokemonProfile pokemon);
        void showWelcomePokemon(PokemonProfile pokemon);
        void openHatchDialog(CommandContext.PokemonHatchRequest request);
    }

    public interface Btw {
        void open(String question, Function<String, String> sideQuestionRunner);
    }

    /** {@code /web}: start (or join) the web gateway and open its URL. */
    public interface WebGateway {
        void startWebGateway();
    }

    /** Every capability the screen installs once the scene is built. */
    record Capabilities(
        Preferences preferences,
        Permissions permissions,
        Agents agents,
        Sandbox sandbox,
        Memory memory,
        Session session,
        Conversation conversation,
        Diagnostics diagnostics,
        Hooks hooks,
        Mcp mcp,
        Tasks tasks,
        Plugins plugins,
        Goal goal,
        Compact compact,
        Pokemon pokemon,
        Btw btw,
        WebGateway webGateway) {
        Capabilities {
            Objects.requireNonNull(preferences, "preferences");
            Objects.requireNonNull(permissions, "permissions");
            Objects.requireNonNull(agents, "agents");
            Objects.requireNonNull(sandbox, "sandbox");
            Objects.requireNonNull(memory, "memory");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(conversation, "conversation");
            Objects.requireNonNull(diagnostics, "diagnostics");
            Objects.requireNonNull(hooks, "hooks");
            Objects.requireNonNull(mcp, "mcp");
            Objects.requireNonNull(tasks, "tasks");
            Objects.requireNonNull(plugins, "plugins");
            Objects.requireNonNull(goal, "goal");
            Objects.requireNonNull(compact, "compact");
            Objects.requireNonNull(pokemon, "pokemon");
            Objects.requireNonNull(btw, "btw");
            Objects.requireNonNull(webGateway, "webGateway");
        }
    }

    private volatile Capabilities capabilities;

    void install(Capabilities capabilities) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    // ── Preferences ─────────────────────────────────────────────────────────

    public void openEffort() {
        Capabilities c = capabilities;
        if (c != null) c.preferences().openEffort();
    }

    public void openModelPicker() {
        Capabilities c = capabilities;
        if (c != null) c.preferences().openModel();
    }

    public void showEffortNotification(String value) {
        Capabilities c = capabilities;
        if (c != null) c.preferences().showEffortNotification(value);
    }

    public void openTheme(String current) {
        Capabilities c = capabilities;
        if (c != null) c.preferences().openTheme(current);
    }

    public void openConfig() {
        Capabilities c = capabilities;
        if (c != null) c.preferences().openConfig();
    }

    public void openStatus() {
        Capabilities c = capabilities;
        if (c != null) c.preferences().openStatus();
    }

    public void openUsage() {
        Capabilities c = capabilities;
        if (c != null) c.preferences().openUsage();
    }

    // ── Permissions / agents / sandbox / memory ─────────────────────────────

    public void openPermissions() {
        Capabilities c = capabilities;
        if (c != null) c.permissions().openRules();
    }

    public void openAddDirectory(String path) {
        Capabilities c = capabilities;
        if (c != null) c.permissions().openAddDirectory(path);
    }

    public void openAgents() {
        Capabilities c = capabilities;
        if (c != null) c.agents().openAgents();
    }

    public void openSandbox() {
        Capabilities c = capabilities;
        if (c != null) c.sandbox().openSandbox();
    }

    public void openMemoryDialog() {
        Capabilities c = capabilities;
        if (c != null) c.memory().openMemoryDialog();
    }

    public void openFileInEditor(Path file) {
        Capabilities c = capabilities;
        if (c != null) c.memory().openFileInEditor(file);
    }

    // ── Session ─────────────────────────────────────────────────────────────

    public void clearConversation() {
        Capabilities c = capabilities;
        if (c != null) c.session().clearConversation();
    }

    public void switchActiveSession(String id) {
        Capabilities c = capabilities;
        if (c != null) c.session().switchActiveSession(id);
    }

    public void resetSessionCost() {
        Capabilities c = capabilities;
        if (c != null) c.session().resetSessionCost();
    }

    public void resumeSession(ResumeRequest request) {
        Capabilities c = capabilities;
        if (c != null) c.session().resume(request);
    }

    public void openMessageSelector() {
        Capabilities c = capabilities;
        if (c != null) c.session().openMessageSelector();
    }

    // ── Conversation tools ──────────────────────────────────────────────────

    public void openHelp() {
        Capabilities c = capabilities;
        if (c != null) c.conversation().openHelp();
    }

    public void openDiff() {
        Capabilities c = capabilities;
        if (c != null) c.conversation().openDiff();
    }

    public void openExport(String content) {
        Capabilities c = capabilities;
        if (c != null) c.conversation().openExport(content);
    }

    public void openCopyPicker(String fullText, List<CopyCommand.CodeBlock> codeBlocks,
                               boolean skipPicker) {
        Capabilities c = capabilities;
        if (c != null) c.conversation().openCopyPicker(fullText, codeBlocks, skipPicker);
    }

    public void showContextVisualization() {
        Capabilities c = capabilities;
        if (c != null) c.conversation().showContextVisualization();
    }

    public void openTagRemoval(CommandContext.TagRemovalRequest request) {
        Capabilities c = capabilities;
        if (c != null) c.conversation().openTagRemoval(request);
    }

    // ── Diagnostics / hooks / MCP / tasks / plugins / goal ──────────────────

    public void openDoctor() {
        Capabilities c = capabilities;
        if (c != null) c.diagnostics().openDoctor();
    }

    public void openStats() {
        Capabilities c = capabilities;
        if (c != null) c.diagnostics().openStats();
    }

    public void openSkills() {
        Capabilities c = capabilities;
        if (c != null) c.diagnostics().openSkills();
    }

    public void openHooks() {
        Capabilities c = capabilities;
        if (c != null) c.hooks().openHooks();
    }

    public void openMcp() {
        Capabilities c = capabilities;
        if (c != null) c.mcp().openMcp();
    }

    public void openTasks() {
        Capabilities c = capabilities;
        if (c != null) c.tasks().openTasks();
    }

    public void openWorkflows() {
        Capabilities c = capabilities;
        if (c != null) c.tasks().openWorkflows();
    }

    public void openWorkflows(String taskId, boolean returnToTasks) {
        Capabilities c = capabilities;
        if (c != null) c.tasks().openWorkflows(taskId, returnToTasks);
    }

    public void openPluginPanel(String args) {
        Capabilities c = capabilities;
        if (c != null) c.plugins().openPluginPanel(args);
    }

    public void openGoal() {
        Capabilities c = capabilities;
        if (c != null) c.goal().openGoal();
    }

    // ── Compact / pokemon / btw ─────────────────────────────────────────────

    public void compactProgress(CompactProgressEvent event) {
        Capabilities c = capabilities;
        if (c != null) c.compact().progress(event);
    }

    public void setWelcomePokemon(PokemonProfile pokemon) {
        Capabilities c = capabilities;
        if (c != null) c.pokemon().setWelcomePokemon(pokemon);
    }

    public void showWelcomePokemon(PokemonProfile pokemon) {
        Capabilities c = capabilities;
        if (c != null) c.pokemon().showWelcomePokemon(pokemon);
    }

    public void openPokemonHatch(CommandContext.PokemonHatchRequest request) {
        Capabilities c = capabilities;
        if (c != null) c.pokemon().openHatchDialog(request);
    }

    public void openBtw(String question, Function<String, String> sideQuestionRunner) {
        Capabilities c = capabilities;
        if (c != null) c.btw().open(question, sideQuestionRunner);
    }

    public void startWebGateway() {
        Capabilities c = capabilities;
        if (c != null) c.webGateway().startWebGateway();
    }
}
