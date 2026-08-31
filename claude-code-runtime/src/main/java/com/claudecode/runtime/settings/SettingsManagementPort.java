package com.claudecode.runtime.settings;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.pokemon.PokemonProfile;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Presentation-neutral application boundary for persisted settings.
 *
 * <ul>
 *   <li>effective configuration
 *       snapshot and setting updates.</li>
 *   <li>
 *       — global/user/project/local preference persistence.</li>
 *   <li>
 *
 *       sandbox and additional-directory persistence.</li>
 * </ul>
 */
public interface SettingsManagementPort {

    Configuration configuration();

    Preferences preferences();

    Sandbox sandbox();

    interface Configuration {
        Map<String, String> values(String workingDirectory);
        void save(String workingDirectory, String key, String value);
        boolean syntaxHighlightingDisabled();
        void saveSyntaxHighlightingDisabled(boolean disabled);
    }

    interface Preferences {
        String theme();
        void saveTheme(String theme);
        String effortLevel();
        void saveEffortLevel(String effort);
        Optional<String> advisorModel();
        void saveAdvisorModel(String model);
        boolean copyFullResponse();
        void saveCopyFullResponse(boolean enabled);
        Optional<PokemonProfile> pokemon();
        void savePokemon(PokemonProfile profile);
        void incrementBtwUseCount();
        boolean hasStoredApiKey();
        List<String> settingSourceLabels(String workingDirectory);
    }

    interface Sandbox {
        SandboxConfig config();
        boolean lockedByPolicy();
        void saveSettings(String workingDirectory, Boolean enabled,
                          Boolean autoAllowBashIfSandboxed,
                          Boolean allowUnsandboxedCommands);
        String addExcludedCommand(String workingDirectory, String pattern);
        void saveAdditionalDirectory(String workingDirectory, String absolutePath);
    }

    static SettingsManagementPort none() {
        return new SettingsManagementPort() {
            private final Configuration configuration = new Configuration() {
                @Override public Map<String, String> values(String workingDirectory) {
                    return Map.of();
                }
                @Override public void save(String workingDirectory, String key, String value) {
                    throw unwired();
                }
                @Override public boolean syntaxHighlightingDisabled() { return false; }
                @Override public void saveSyntaxHighlightingDisabled(boolean disabled) {
                    throw unwired();
                }
            };
            private final Preferences preferences = new Preferences() {
                @Override public String theme() { return "dark"; }
                @Override public void saveTheme(String theme) { throw unwired(); }
                @Override public String effortLevel() { return null; }
                @Override public void saveEffortLevel(String effort) { throw unwired(); }
                @Override public Optional<String> advisorModel() { return Optional.empty(); }
                @Override public void saveAdvisorModel(String model) { throw unwired(); }
                @Override public boolean copyFullResponse() { return false; }
                @Override public void saveCopyFullResponse(boolean enabled) { throw unwired(); }
                @Override public Optional<PokemonProfile> pokemon() { return Optional.empty(); }
                @Override public void savePokemon(PokemonProfile profile) { throw unwired(); }
                @Override public void incrementBtwUseCount() { throw unwired(); }
                @Override public boolean hasStoredApiKey() { return false; }
                @Override public List<String> settingSourceLabels(String workingDirectory) {
                    return List.of();
                }
            };
            private final Sandbox sandbox = new Sandbox() {
                @Override public SandboxConfig config() { return SandboxConfig.disabled(); }
                @Override public boolean lockedByPolicy() { return false; }
                @Override public void saveSettings(String workingDirectory, Boolean enabled,
                        Boolean autoAllowBashIfSandboxed, Boolean allowUnsandboxedCommands) {
                    throw unwired();
                }
                @Override public String addExcludedCommand(String workingDirectory, String pattern) {
                    throw unwired();
                }
                @Override public void saveAdditionalDirectory(
                        String workingDirectory, String absolutePath) {
                    throw unwired();
                }
            };

            @Override public Configuration configuration() { return configuration; }
            @Override public Preferences preferences() { return preferences; }
            @Override public Sandbox sandbox() { return sandbox; }
        };
    }

    private static IllegalStateException unwired() {
        return new IllegalStateException("Settings management is not wired");
    }
}
