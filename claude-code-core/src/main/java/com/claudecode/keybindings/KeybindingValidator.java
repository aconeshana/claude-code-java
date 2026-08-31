package com.claudecode.keybindings;


import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a user config and reports warnings.
 */
public final class KeybindingValidator {

    private KeybindingValidator() {}


    public enum WarningType {
        PARSE_ERROR, DUPLICATE, RESERVED, INVALID_CONTEXT, INVALID_ACTION
    }


    public enum Severity { ERROR, WARNING }

/**
     * A single validation issue.
     */
    public record KeybindingWarning(
        WarningType type,
        Severity severity,
        String message,
        String key,
        String context,
        String action,
        String suggestion
    ) {}

    /** One structurally-valid block earmarked for cross-block checks. */
    private record ValidBlock(String context, Map<String, String> bindings) {}

    private static final List<String> VALID_CONTEXTS =
        DefaultBindings.BLOCKS.stream().map(DefaultBindings.Block::context).toList();

    // ── Public entry points ────────────────────────────────────────────────

    /**
     * Parse {@code json} and run all validations. On a JSON parse failure
     * returns a single {@link WarningType#PARSE_ERROR}.
     */
    public static List<KeybindingWarning> validate(String json) {
        JsonNode root;
        try {
            root = JsonUtils.getMapper().readTree(json);
        } catch (Exception e) {
            return List.of(new KeybindingWarning(
                WarningType.PARSE_ERROR, Severity.ERROR,
                "Invalid JSON: " + e.getMessage(), null, null, null, null));
        }
        List<KeybindingWarning> warnings = validate(root);
// Duplicate-key detection must run on the RAW text: Jackson's readTree silently keeps the
// last value for a repeated field name, so the parsed tree can never reveal a duplicate.
        try {
            warnings.addAll(checkDuplicateKeysInJson(json));
        } catch (Exception _) {
            // Malformed text — structural errors are reported elsewhere.
        }
        return dedupe(warnings);
    }

    /**
     * Validate a user keybindings <em>config file</em> (what's actually on disk).
     */
    public static List<KeybindingWarning> validateFile(String rawJson) {
        JsonNode root;
        try {
            root = JsonUtils.getMapper().readTree(rawJson);
        } catch (Exception e) {
            return List.of(new KeybindingWarning(
                WarningType.PARSE_ERROR, Severity.ERROR,
                "Invalid JSON: " + e.getMessage(), null, null, null, null));
        }
        JsonNode userBlocks;
        if (root.isArray()) {
            userBlocks = root;
        } else if (root.isObject() && root.has("bindings") && root.get("bindings").isArray()) {
            userBlocks = root.get("bindings");
        } else {
            return List.of(new KeybindingWarning(
                WarningType.PARSE_ERROR, Severity.ERROR,
                "keybindings.json must have a \"bindings\" array",
                null, null, null, "Use format: { \"bindings\": [ ... ] }"));
        }
        return validate(userBlocks.toString());
    }

    /** Validate an already-parsed tree (exposed for testing). */
    static List<KeybindingWarning> validate(JsonNode root) {
        List<KeybindingWarning> warnings = new ArrayList<>();
        if (root == null || !root.isArray()) {
            warnings.add(new KeybindingWarning(
                WarningType.PARSE_ERROR, Severity.ERROR,
                "keybindings.json must contain an array", null, null, null,
                "Wrap your bindings in [ ]"));
            return dedupe(warnings);
        }

        List<ValidBlock> validBlocks = new ArrayList<>();
        boolean allStructural = true;
        int index = 0;
        var blockIt = root.elements();
        while (blockIt.hasNext()) {
            JsonNode block = blockIt.next();
            warnings.addAll(validateBlock(block, index));
            if (isKeybindingBlock(block)) {
                JsonNode ctx = block.get("context");
                Map<String, String> binds = new HashMap<>();
                block.get("bindings").fields().forEachRemaining(
                    e -> binds.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
                validBlocks.add(new ValidBlock(ctx.asText(), binds));
            } else {
                allStructural = false;
            }
            index++;
        }

        if (allStructural && !validBlocks.isEmpty()) {
            warnings.addAll(checkDuplicates(validBlocks));
            warnings.addAll(checkReservedShortcuts(validBlocks));
        }

        return dedupe(warnings);
    }

    // ── Context validity ───────────────────────────────────────────────────

    static boolean isValidContext(String value) {
        return VALID_CONTEXTS.contains(value);
    }



    private static List<KeybindingWarning> validateBlock(JsonNode block, int blockIndex) {
        List<KeybindingWarning> warnings = new ArrayList<>();
        String blockLabel = "Keybinding block " + (blockIndex + 1);

        if (!block.isObject()) {
            warnings.add(new KeybindingWarning(
                WarningType.PARSE_ERROR, Severity.ERROR,
                blockLabel + " is not an object", null, null, null, null));
            return warnings;
        }

        final String contextName;
        JsonNode rawContext = block.get("context");
        if (rawContext == null || !rawContext.isTextual()) {
            warnings.add(new KeybindingWarning(
                WarningType.PARSE_ERROR, Severity.ERROR,
                blockLabel + " missing \"context\" field", null, null, null, null));
            contextName = null;
        } else if (!isValidContext(rawContext.asText())) {
            warnings.add(new KeybindingWarning(
                WarningType.INVALID_CONTEXT, Severity.ERROR,
                "Unknown context \"" + rawContext.asText() + "\"",
                null, rawContext.asText(), null,
                "Valid contexts: " + String.join(", ", VALID_CONTEXTS)));
            contextName = null;
        } else {
            contextName = rawContext.asText();
        }

        JsonNode bindings = block.get("bindings");
        if (!bindings.isObject()) {
            warnings.add(new KeybindingWarning(
                WarningType.PARSE_ERROR, Severity.ERROR,
                blockLabel + " missing \"bindings\" field", null, null, null, null));
            return warnings;
        }

        bindings.fields().forEachRemaining(e -> {
            String key = e.getKey();
            JsonNode action = e.getValue();

            KeybindingWarning keyError = validateKeystroke(key);
            if (keyError != null) {
                warnings.add(new KeybindingWarning(
                    keyError.type(), keyError.severity(), keyError.message(),
                    key, contextName, keyError.action(), keyError.suggestion()));
            }

            if (action != null && !action.isNull() && !action.isTextual()) {
                warnings.add(new KeybindingWarning(
                    WarningType.INVALID_ACTION, Severity.ERROR,
                    "Invalid action for \"" + key + "\": must be a string or null",
                    key, contextName, null, null));
            } else if (action != null && action.isTextual()) {
                String actionStr = action.asText();
                if (Strings.CS.startsWith(actionStr, "command:")) {
                    if (!actionStr.matches("^command:[a-zA-Z0-9:_\\-]+$")) {
                        warnings.add(new KeybindingWarning(
                            WarningType.INVALID_ACTION, Severity.WARNING,
                            "Invalid command binding \"" + actionStr + "\" for \"" + key
                                + "\": command name may only contain alphanumeric characters, "
                                + "colons, hyphens, and underscores",
                            key, contextName, actionStr, null));
                    }
                    if (contextName != null && !Strings.CS.equals(contextName, "Chat")) {
                        warnings.add(new KeybindingWarning(
                            WarningType.INVALID_ACTION, Severity.WARNING,
                            "Command binding \"" + actionStr + "\" must be in \"Chat\" context, not \""
                                + contextName + "\"",
                            key, contextName, actionStr,
                            "Move this binding to a block with \"context\": \"Chat\""));
                    }
                } else if (Strings.CS.equals(actionStr, "voice:pushToTalk")) {
                    KeystrokeParser.Chord chord = KeystrokeParser.parseChord(key);
                    KeystrokeParser.Keystroke ks = chord.keystrokes.isEmpty()
                        ? KeystrokeParser.Keystroke.empty() : chord.keystrokes.getFirst();
                    boolean bare = !ks.ctrl() && !ks.alt() && !ks.shift()
                        && !ks.meta() && !ks.superMod() && ks.key().matches("^[a-z]$");
                    if (bare) {
                        warnings.add(new KeybindingWarning(
                            WarningType.INVALID_ACTION, Severity.WARNING,
                            "Binding \"" + key + "\" to voice:pushToTalk prints into the input during "
                                + "warmup; use space or a modifier combo like meta+k",
                            key, contextName, actionStr, null));
                    }
                }
            }
        });

        return warnings;
    }



    private static KeybindingWarning validateKeystroke(String keystroke) {
        for (String part : keystroke.toLowerCase(Locale.ROOT).split("\\+")) {
            if (part.trim().isEmpty()) {
                return new KeybindingWarning(
                    WarningType.PARSE_ERROR, Severity.ERROR,
                    "Empty key part in \"" + keystroke + "\"",
                    keystroke, null, null, "Remove extra \"+\" characters");
            }
        }
        KeystrokeParser.Keystroke parsed = KeystrokeParser.parseKeystroke(keystroke);
        if (parsed.key().isEmpty() && !parsed.ctrl() && !parsed.alt()
                && !parsed.shift() && !parsed.meta()) {
            return new KeybindingWarning(
                WarningType.PARSE_ERROR, Severity.ERROR,
                "Could not parse keystroke \"" + keystroke + "\"",
                keystroke, null, null, null);
        }
        return null;
    }



    /**
     * Scans the RAW JSON text for a key repeated inside the same {@code bindings} object (e.g.
     */
    private static List<KeybindingWarning> checkDuplicateKeysInJson(String rawJson) {
        List<KeybindingWarning> warnings = new ArrayList<>();
        if (StringUtils.isBlank(rawJson)) return warnings;

        try (JsonParser p = JsonUtils.getMapper().getFactory().createParser(rawJson)) {
            // Field name that introduced the currently-open object; "" means a
            // top-level array element (a keybinding block), null means root.
            Deque<String> introStack = new ArrayDeque<>();
            String pendingField = null;
            String currentContext = "unknown";
            Map<String, Integer> counts = null;

            JsonToken t;
            while ((t = p.nextToken()) != null) {
                switch (t) {
                    case START_OBJECT: {
                        boolean isBindings = pendingField != null && Strings.CS.equals(pendingField, "bindings");
                        introStack.push(pendingField == null ? "" : pendingField);
                        if (isBindings) {
                            counts = new HashMap<>();
                        } else if (pendingField == null) {
                            // Entering a top-level block — reset captured context.
                            currentContext = "unknown";
                        }
                        pendingField = null;
                        break;
                    }
                    case END_OBJECT: {
                        String intro = introStack.pop();
                        if (Strings.CS.equals("bindings", intro) && counts != null) {
                            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                                if (e.getValue() >= 2) {
                                    warnings.add(new KeybindingWarning(
                                        WarningType.DUPLICATE, Severity.WARNING,
                                        "Duplicate key \"" + e.getKey() + "\" in "
                                            + currentContext + " bindings",
                                        e.getKey(), currentContext, null,
                                        "This key appears multiple times in the same context. "
                                            + "JSON uses the last value, earlier values are ignored."));
                                }
                            }
                            counts = null;
                        }
                        break;
                    }
                    case FIELD_NAME:
                        pendingField = p.currentName();
                        // Direct child of a bindings object → track for dupes.
                        if (counts != null && !introStack.isEmpty()
                                && Strings.CS.equals("bindings", introStack.peek())) {
                            counts.merge(pendingField, 1, Integer::sum);
                        }
                        break;
                    case VALUE_STRING:
                        if (pendingField != null && Strings.CS.equals(pendingField, "context")) {
                            currentContext = p.getText();
                        }
                        pendingField = null;
                        break;
                    default:
                        pendingField = null;
                }
            }
        } catch (Exception _) {
            // Malformed text — structural errors are reported elsewhere.
        }
        return warnings;
    }



    private static List<KeybindingWarning> checkDuplicates(List<ValidBlock> blocks) {
        List<KeybindingWarning> warnings = new ArrayList<>();
        Map<String, Map<String, String>> seenByContext = new HashMap<>();

        for (ValidBlock block : blocks) {
            Map<String, String> contextMap =
                seenByContext.computeIfAbsent(block.context(), _ -> new HashMap<>());
            for (Map.Entry<String, String> e : block.bindings().entrySet()) {
                String normalized = ReservedShortcuts.normalizeKeyForComparison(e.getKey());
                String action = e.getValue();
                String existing = contextMap.get(normalized);
                if (existing != null && !existing.equals(action)) {
                    warnings.add(new KeybindingWarning(
                        WarningType.DUPLICATE, Severity.WARNING,
                        "Duplicate binding \"" + e.getKey() + "\" in " + block.context() + " context",
                        e.getKey(), block.context(), action,
                        "Previously bound to \"" + existing + "\". Only the last binding will be used."));
                }
                contextMap.put(normalized, action);
            }
        }
        return warnings;
    }



    private static List<KeybindingWarning> checkReservedShortcuts(List<ValidBlock> blocks) {
        List<KeybindingWarning> warnings = new ArrayList<>();
        List<ReservedShortcuts.ReservedShortcut> reserved = ReservedShortcuts.getReservedShortcuts();

        for (ValidBlock block : blocks) {
            for (Map.Entry<String, String> e : block.bindings().entrySet()) {
                KeystrokeParser.Chord chord = KeystrokeParser.parseChord(e.getKey());
                String keyDisplay = KeystrokeParser.chordToString(chord);
                String normalizedKey = ReservedShortcuts.normalizeKeyForComparison(keyDisplay);
                for (ReservedShortcuts.ReservedShortcut res : reserved) {
                    if (ReservedShortcuts.normalizeKeyForComparison(res.key()).equals(normalizedKey)) {
                        warnings.add(new KeybindingWarning(
                            WarningType.RESERVED,
                            res.severity() == ReservedShortcuts.Severity.ERROR
                                ? Severity.ERROR : Severity.WARNING,
                            "\"" + keyDisplay + "\" may not work: " + res.reason(),
                            keyDisplay, block.context(), e.getValue(), null));
                    }
                }
            }
        }
        return warnings;
    }

    // ── De-duplication + formatting ────────────────────────────────────────

    private static List<KeybindingWarning> dedupe(List<KeybindingWarning> warnings) {
        Set<String> seen = new HashSet<>();
        List<KeybindingWarning> out = new ArrayList<>();
        for (KeybindingWarning w : warnings) {
            String key = w.type() + ":" + (w.key() == null ? "" : w.key()) + ":"
                + (w.context() == null ? "" : w.context());
            if (seen.add(key)) out.add(w);
        }
        return out;
    }

/**
     * Format one warning for terminal display.
     */
    public static String formatWarning(KeybindingWarning w) {
        String icon = w.severity() == Severity.ERROR ? "✗" : "⚠";
        String msg = icon + " Keybinding " + w.severity().name().toLowerCase(Locale.ROOT) + ": " + w.message();
        if (StringUtils.isNotBlank(w.suggestion())) {
            msg += "\n  " + w.suggestion();
        }
        return msg;
    }

/**
     * Format all warnings, grouped by severity.
     */
    public static String formatWarnings(List<KeybindingWarning> warnings) {
        if (warnings.isEmpty()) return "";
        List<KeybindingWarning> errors = new ArrayList<>();
        List<KeybindingWarning> warns = new ArrayList<>();
        for (KeybindingWarning w : warnings) {
            (w.severity() == Severity.ERROR ? errors : warns).add(w);
        }

        List<String> lines = new ArrayList<>();
        if (!errors.isEmpty()) {
            lines.add("Found " + errors.size() + " keybinding "
                + plural(errors.size(), "error") + ":");
            for (KeybindingWarning e : errors) lines.add(formatWarning(e));
        }
        if (!warns.isEmpty()) {
            if (!lines.isEmpty()) lines.add("");
            lines.add("Found " + warns.size() + " keybinding "
                + plural(warns.size(), "warning") + ":");
            for (KeybindingWarning w : warns) lines.add(formatWarning(w));
        }
        return String.join("\n", lines);
    }

    private static String plural(int n, String word) {
        return n == 1 ? word : word + "s";
    }

    private static boolean isKeybindingBlock(JsonNode block) {
        if (!block.isObject()) return false;
        JsonNode ctx = block.get("context");
        JsonNode binds = block.get("bindings");
        return ctx != null && ctx.isTextual() && binds != null && binds.isObject();
    }
}
