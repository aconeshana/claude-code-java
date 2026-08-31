package com.claudecode.ui.lanterna.repl;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Late-bound command-to-UI capability bridge.
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
    }

    private volatile Preferences preferences;
    private volatile Permissions permissions;
    private volatile Agents agents;
    private volatile Sandbox sandbox;
    private volatile Memory memory;
    private volatile Session session;

    void install(Preferences preferences, Permissions permissions,
                 Agents agents, Sandbox sandbox, Memory memory, Session session) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.session = Objects.requireNonNull(session, "session");
    }

    public void openEffort() {
        Preferences feature = preferences;
        if (feature != null) feature.openEffort();
    }

    public void openModelPicker() {
        Preferences feature = preferences;
        if (feature != null) feature.openModel();
    }

    public void showEffortNotification(String value) {
        Preferences feature = preferences;
        if (feature != null) feature.showEffortNotification(value);
    }

    public void openTheme(String current) {
        Preferences feature = preferences;
        if (feature != null) feature.openTheme(current);
    }

    public void openConfig() {
        Preferences feature = preferences;
        if (feature != null) feature.openConfig();
    }

    public void openStatus() {
        Preferences feature = preferences;
        if (feature != null) feature.openStatus();
    }

    public void openUsage() {
        Preferences feature = preferences;
        if (feature != null) feature.openUsage();
    }

    public void openPermissions() {
        Permissions feature = permissions;
        if (feature != null) feature.openRules();
    }

    public void openAddDirectory(String path) {
        Permissions feature = permissions;
        if (feature != null) feature.openAddDirectory(path);
    }

    public void openAgents() {
        Agents feature = agents;
        if (feature != null) feature.openAgents();
    }

    public void openSandbox() {
        Sandbox feature = sandbox;
        if (feature != null) feature.openSandbox();
    }

    public void openMemoryDialog() {
        Memory feature = memory;
        if (feature != null) feature.openMemoryDialog();
    }

    public void openFileInEditor(Path file) {
        Memory feature = memory;
        if (feature != null) feature.openFileInEditor(file);
    }

    public void clearConversation() {
        Session feature = session;
        if (feature != null) feature.clearConversation();
    }

    public void switchActiveSession(String id) {
        Session feature = session;
        if (feature != null) feature.switchActiveSession(id);
    }

    public void resetSessionCost() {
        Session feature = session;
        if (feature != null) feature.resetSessionCost();
    }
}
