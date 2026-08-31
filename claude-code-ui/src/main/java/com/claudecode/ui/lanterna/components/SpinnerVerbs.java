package com.claudecode.ui.lanterna.components;

import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import com.claudecode.ui.lanterna.features.settings.UiSettings;

/**
 * Spinner verb selection — data + selection functions used by the streaming status line ({@code "✻
 * Brewing… (5s)"}) and turn-complete recap ({@code "✻ Brewed for 5s"}).
 */
public final class SpinnerVerbs {

    private static final List<String> BUILT_IN_ACTIVE     = loadResource("/spinner/active-verbs.txt");
    private static final List<String> BUILT_IN_COMPLETION = loadResource("/spinner/completion-verbs.txt");
    private static final List<String> ACTIVE_VERBS        = applySettingsOverride(BUILT_IN_ACTIVE);
    private static final List<String> COMPLETION_VERBS    = BUILT_IN_COMPLETION;





    private static final Map<String, String> TOOL_TO_VERB = Map.ofEntries(
        Map.entry("Bash",         "Running"),
        Map.entry("REPL",         "Running"),
        Map.entry("PowerShell",   "Running"),
        Map.entry("Read",         "Reading"),
        Map.entry("Write",        "Writing"),
        Map.entry("Edit",         "Editing"),
        Map.entry("NotebookEdit", "Editing"),
        Map.entry("MultiEdit",    "Editing"),
        Map.entry("Grep",         "Searching"),
        Map.entry("WebSearch",    "Searching"),
        Map.entry("Glob",         "Globbing"),
        Map.entry("WebFetch",     "Fetching"),
        Map.entry("Agent",        "Delegating"),
        Map.entry("Task",         "Delegating"),
        Map.entry("TodoWrite",    "Planning"),
        Map.entry("TaskCreate",   "Planning")
    );

    private SpinnerVerbs() {}


    public static List<String> activeVerbs()     { return ACTIVE_VERBS; }

/**
     * Immutable view of the resolved past-tense list.
     */
    public static List<String> completionVerbs() { return COMPLETION_VERBS; }

    /** Pick a random present-participle verb for the streaming spinner. */
    public static String randomActive() {
        return ACTIVE_VERBS.get(ThreadLocalRandom.current().nextInt(ACTIVE_VERBS.size()));
    }

    /** Pick a random past-tense verb for the turn-complete recap. */
    public static String randomCompleted() {
        return COMPLETION_VERBS.get(ThreadLocalRandom.current().nextInt(COMPLETION_VERBS.size()));
    }

    /**
     * Map a tool name to its fixed spinner verb. Tools with no assignment
     * (and null / unknown names) get a random active verb.
     */
    public static String forTool(String toolName) {
        if (toolName == null) return randomActive();
        String fixed = TOOL_TO_VERB.get(toolName);
        return fixed != null ? fixed : randomActive();
    }

    /**
     * Package-private for unit testing the splice logic without touching the real.
     */
    static List<String> spliceUserOverride(List<String> builtIn, SpinnerVerbsOverride override) {
        if (override == null || override.verbs() == null) return builtIn;
        List<String> user = override.verbs();
        if (Strings.CS.equals("replace", override.mode())) {
            return user.isEmpty() ? builtIn : List.copyOf(user);
        }

// unknown or missing, so we match that (permissive) behaviour.
        List<String> merged = new ArrayList<>(builtIn.size() + user.size());
        merged.addAll(builtIn);
        merged.addAll(user);
        return List.copyOf(merged);
    }


    record SpinnerVerbsOverride(String mode, List<String> verbs) {}

    /**
     * Read and apply the splice.
     */
    private static List<String> applySettingsOverride(List<String> builtIn) {
        SpinnerVerbsOverride override = readSpinnerVerbsFromSettings();
        return spliceUserOverride(builtIn, override);
    }

    private static SpinnerVerbsOverride readSpinnerVerbsFromSettings() {
        JsonNode node = UiSettings.readEffectiveSetting("spinnerVerbs");
        if (node == null || !node.isObject()) return null;
        String mode = node.hasNonNull("mode") ? node.get("mode").asText() : "append";
        JsonNode verbsNode = node.get("verbs");
        if (verbsNode == null || !verbsNode.isArray()) return null;
        List<String> verbs = new ArrayList<>(verbsNode.size());
        for (JsonNode v : verbsNode) {
            if (v == null || !v.isTextual()) continue;
            String s = v.asText().strip();
            if (!s.isEmpty()) verbs.add(s);
        }
        return new SpinnerVerbsOverride(mode, verbs);
    }

    /**
     * Load a whitespace-trimmed list of non-empty, non-comment lines from a
     * classpath resource. {@code #}-prefixed lines are treated as comments.
     * Failure is fatal at class-load time — the resource is a build-time
     * artifact packaged into the JAR, and its absence would silently break
     * the spinner.
     */
    private static List<String> loadResource(String path) {
        InputStream in = SpinnerVerbs.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("Missing packaged resource: " + path);
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || Strings.CS.startsWith(trimmed, "#")) continue;
                lines.add(trimmed);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + path, e);
        }
        if (lines.isEmpty()) {
            throw new IllegalStateException("Empty verb list: " + path);
        }
        return List.copyOf(lines);
    }
}
