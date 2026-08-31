package com.claudecode.commands.testing;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.pokemon.PokemonProfile;
import com.claudecode.runtime.settings.SettingsManagementPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FakeSettingsManagementPort implements SettingsManagementPort {
    public final Map<String, String> values = new LinkedHashMap<>();
    public final List<String> writes = new ArrayList<>();
    public String theme = "dark";
    public String effort;
    public String advisor;
    public boolean copyFullResponse;
    public PokemonProfile pokemon;
    public boolean syntaxDisabled;
    public int btwCount;
    public boolean storedApiKey;
    public List<String> sourceLabels = List.of();
    public SandboxConfig sandboxConfig = SandboxConfig.disabled();
    public boolean sandboxLocked;
    public final List<String> excludedCommands = new ArrayList<>();
    public final List<String> additionalDirectories = new ArrayList<>();

    private final Configuration configuration = new Configuration() {
        @Override public Map<String, String> values(String workingDirectory) {
            return new LinkedHashMap<>(values);
        }
        @Override public void save(String cwd, String key, String value) {
            values.put(key, value);
            writes.add(key + "=" + value);
        }
        @Override public boolean syntaxHighlightingDisabled() { return syntaxDisabled; }
        @Override public void saveSyntaxHighlightingDisabled(boolean disabled) {
            syntaxDisabled = disabled;
        }
    };
    private final Preferences preferences = new Preferences() {
        @Override public String theme() { return theme; }
        @Override public void saveTheme(String value) { theme = value; }
        @Override public String effortLevel() { return effort; }
        @Override public void saveEffortLevel(String value) { effort = value; }
        @Override public Optional<String> advisorModel() { return Optional.ofNullable(advisor); }
        @Override public void saveAdvisorModel(String value) { advisor = value; }
        @Override public boolean copyFullResponse() { return copyFullResponse; }
        @Override public void saveCopyFullResponse(boolean value) { copyFullResponse = value; }
        @Override public Optional<PokemonProfile> pokemon() { return Optional.ofNullable(pokemon); }
        @Override public void savePokemon(PokemonProfile value) { pokemon = value; }
        @Override public void incrementBtwUseCount() { btwCount++; }
        @Override public boolean hasStoredApiKey() { return storedApiKey; }
        @Override public List<String> settingSourceLabels(String cwd) { return sourceLabels; }
    };
    private final Sandbox sandbox = new Sandbox() {
        @Override public SandboxConfig config() { return sandboxConfig; }
        @Override public boolean lockedByPolicy() { return sandboxLocked; }
        @Override public void saveSettings(String cwd, Boolean enabled,
                Boolean autoAllowBashIfSandboxed, Boolean allowUnsandboxedCommands) {
            writes.add("sandbox=" + enabled + "," + autoAllowBashIfSandboxed
                + "," + allowUnsandboxedCommands);
        }
        @Override public String addExcludedCommand(String cwd, String pattern) {
            if (!excludedCommands.contains(pattern)) excludedCommands.add(pattern);
            return ".claude/settings.local.json";
        }
        @Override public void saveAdditionalDirectory(String cwd, String absolutePath) {
            if (!additionalDirectories.contains(absolutePath)) {
                additionalDirectories.add(absolutePath);
            }
        }
    };

    @Override public Configuration configuration() { return configuration; }
    @Override public Preferences preferences() { return preferences; }
    @Override public Sandbox sandbox() { return sandbox; }
}
