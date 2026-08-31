package com.claudecode.runtime.plugins;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Presentation- and loader-neutral definition of one plugin markdown command.
 */
public record PluginCommandDefinition(
    String name,
    String description,
    String argumentHint,
    List<String> argNames,
    String prompt,
    String pluginName,
    boolean hidden,
    List<String> allowedTools,
    String model,
    String effort,
    boolean disableModelInvocation,
    String userFacingName,
    String whenToUse,
    String version,
    String progressMessage,
    int contentLength,
    String source,
    String loadedFrom,
    boolean hasUserSpecifiedDescription,
    String shell) {

    public PluginCommandDefinition {
        argNames = argNames == null ? List.of() : List.copyOf(argNames);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        userFacingName = StringUtils.isBlank(userFacingName) ? name : userFacingName;
        progressMessage = StringUtils.isBlank(progressMessage) ? "running" : progressMessage;
        source = StringUtils.isBlank(source) ? "plugin" : source;
        shell = StringUtils.isBlank(shell) ? null : shell;
        if (contentLength < 0) contentLength = 0;
    }

    public static Builder builder(String name, String prompt, String pluginName) {
        return new Builder(name, prompt, pluginName);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private String name;
        private String description;
        private String argumentHint;
        private List<String> argNames = List.of();
        private String prompt;
        private String pluginName;
        private boolean hidden;
        private List<String> allowedTools = List.of();
        private String model;
        private String effort;
        private boolean disableModelInvocation;
        private String userFacingName;
        private String whenToUse;
        private String version;
        private String progressMessage = "running";
        private int contentLength;
        private String source = "plugin";
        private String loadedFrom;
        private boolean hasUserSpecifiedDescription;
        private String shell;

        private Builder(String name, String prompt, String pluginName) {
            this.name = name;
            this.prompt = prompt;
            this.pluginName = pluginName;
            userFacingName = name;
            contentLength = prompt == null ? 0 : prompt.length();
        }

        private Builder(PluginCommandDefinition value) {
            name = value.name;
            description = value.description;
            argumentHint = value.argumentHint;
            argNames = value.argNames;
            prompt = value.prompt;
            pluginName = value.pluginName;
            hidden = value.hidden;
            allowedTools = value.allowedTools;
            model = value.model;
            effort = value.effort;
            disableModelInvocation = value.disableModelInvocation;
            userFacingName = value.userFacingName;
            whenToUse = value.whenToUse;
            version = value.version;
            progressMessage = value.progressMessage;
            contentLength = value.contentLength;
            source = value.source;
            loadedFrom = value.loadedFrom;
            hasUserSpecifiedDescription = value.hasUserSpecifiedDescription;
            shell = value.shell;
        }

        public Builder name(String v) { name = v; return this; }
        public Builder description(String v) { description = v; return this; }
        public Builder argumentHint(String v) { argumentHint = v; return this; }
        public Builder argNames(List<String> v) { argNames = v; return this; }
        public Builder prompt(String v) { prompt = v; return this; }
        public Builder pluginName(String v) { pluginName = v; return this; }
        public Builder hidden(boolean v) { hidden = v; return this; }
        public Builder allowedTools(List<String> v) { allowedTools = v; return this; }
        public Builder model(String v) { model = v; return this; }
        public Builder effort(String v) { effort = v; return this; }
        public Builder disableModelInvocation(boolean v) { disableModelInvocation = v; return this; }
        public Builder userFacingName(String v) { userFacingName = v; return this; }
        public Builder whenToUse(String v) { whenToUse = v; return this; }
        public Builder version(String v) { version = v; return this; }
        public Builder progressMessage(String v) { progressMessage = v; return this; }
        public Builder contentLength(int v) { contentLength = v; return this; }
        public Builder source(String v) { source = v; return this; }
        public Builder loadedFrom(String v) { loadedFrom = v; return this; }
        public Builder hasUserSpecifiedDescription(boolean v) { hasUserSpecifiedDescription = v; return this; }
        public Builder shell(String v) { shell = v; return this; }

        public PluginCommandDefinition build() {
            return new PluginCommandDefinition(name, description, argumentHint, argNames, prompt,
                pluginName, hidden, allowedTools, model, effort, disableModelInvocation,
                userFacingName, whenToUse, version, progressMessage, contentLength, source,
                loadedFrom, hasUserSpecifiedDescription, shell);
        }
    }
}
