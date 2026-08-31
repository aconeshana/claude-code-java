package com.claudecode.core.util;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.YamlUtils;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses YAML frontmatter from markdown skill files.
 * Extracts metadata between --- markers: name, description, allowedTools, paths.
 *
 * <p>Sunk from {@code com.claudecode.tools.skills} to core so that both
 * {@code claude-code-tools} and {@code claude-code-services} can share it
 * without {@code services} depending on {@code tools} (a layering violation
 * that previously forced every services→tools consumer into a cycle).
 *
 * <ul>
 *   <li> —
 *       {@code parsePositiveIntFromFrontmatter}</li>
 *   <li> via {@code parseFrontmatter} —
 *       YAML quoted scalars plus folded ({@code >}) and literal ({@code |})
 *       block descriptions used by real {@code SKILL.md} files, while preserving
 *       the markdown body byte-for-byte after the frontmatter delimiter.</li>
 * </ul>
 */
public class FrontmatterParser {

    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("\\A---\\s*\\n(.*?)\\n---\\s*\\n?(.*)", Pattern.DOTALL);
    private static final Pattern INTEGER_PREFIX_PATTERN = Pattern.compile("^[+-]?\\d+");
    private static final Pattern STRICT_BOOLEAN_SCALAR_PATTERN =
            Pattern.compile("^(\\s*[^#:\\n]+:\\s*)([^#\\s]+)(\\s*(?:#.*)?)$");
    private static final Pattern SIMPLE_KEY_VALUE_PATTERN =
            Pattern.compile("^([a-zA-Z_-]+):\\s+(.+)$");
    private static final Pattern PROBLEMATIC_PLAIN_SCALAR_PATTERN =
            Pattern.compile(".*[{}\\[\\]*&#!|>%@`].*");
    private static final Pattern BLOCK_SCALAR_INDICATOR_PATTERN =
            Pattern.compile("[>|][+-]?");
    private static final Pattern BRACE_EXPANSION_PATTERN =
            Pattern.compile("^([^{}]*)\\{([^}]+)}(.*)$");

    /** Shared stateless parser for production scanners and loaders. */
    public static FrontmatterParser shared() {
        return SharedHolder.INSTANCE;
    }

    private static final class SharedHolder {
        private static final FrontmatterParser INSTANCE = new FrontmatterParser();
    }

    /**
     * Parse a markdown file content into frontmatter metadata and body content.
     *
     * @param content the full file content
     * @return parsed result with metadata map and body
     */
    public ParseResult parse(String content) {
        if (content == null) {
            return new ParseResult(Map.of(), "");
        }

        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.matches()) {
            return new ParseResult(Map.of(), content);
        }

        String yamlSection = matcher.group(1);
        String body = matcher.group(2);

        Map<String, Object> metadata = parseYaml(yamlSection);
        return new ParseResult(metadata, body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(String yaml) {
        String bunCompatibleYaml = preserveStrictBooleanScalars(yaml);
        try {
            Object parsed = YamlUtils.parse(bunCompatibleYaml);
            if (parsed instanceof Map<?, ?> map) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
        } catch (Exception _) {
            try {
                Object parsed = YamlUtils.parse(quoteProblematicValues(bunCompatibleYaml));
                if (parsed instanceof Map<?, ?> map) {
                    return new LinkedHashMap<>((Map<String, Object>) map);
                }
            } catch (Exception _) {}
        }
        return parseYamlSimple(yaml);
    }

    /**
     * Jackson's YAML 1.1 resolver coerces legacy/case variants such as {@code TRUE}, {@code yes}, and
     * {@code on} to booleans.
     */
    private static String preserveStrictBooleanScalars(String yaml) {
        String[] lines = yaml.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = STRICT_BOOLEAN_SCALAR_PATTERN.matcher(lines[i]);
            if (!matcher.matches()) continue;
            String scalar = matcher.group(2);
            String lower = scalar.toLowerCase(Locale.ROOT);
            boolean legacyBoolean = switch (lower) {
                case "true", "false", "yes", "no", "on", "off" -> true;
                default -> false;
            };
            if (legacyBoolean && !Strings.CS.equals("true", scalar) && !Strings.CS.equals("false", scalar)) {
                lines[i] = matcher.group(1) + '"' + scalar + '"' + matcher.group(3);
            }
        }
        return String.join("\n", lines);
    }

    private static String quoteProblematicValues(String yaml) {
        String[] lines = yaml.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = SIMPLE_KEY_VALUE_PATTERN.matcher(lines[i]);
            if (!matcher.matches()) continue;
            String value = matcher.group(2);
            boolean quoted = value.length() >= 2
                && ((Strings.CS.startsWith(value, "\"") && Strings.CS.endsWith(value, "\""))
                    || (Strings.CS.startsWith(value, "'") && Strings.CS.endsWith(value, "'")));
            if (!quoted && (PROBLEMATIC_PLAIN_SCALAR_PATTERN.matcher(value).matches()
                    || Strings.CS.contains(value, ": "))) {
                lines[i] = matcher.group(1) + ": \""
                    + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
        }
        return String.join("\n", lines);
    }

    /**
     * Simple YAML-like parser for frontmatter key-value pairs.
     * Supports: string values, list values (- item syntax).
     */
    Map<String, Object> parseYamlSimple(String yaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        String currentKey = null;
        List<String> currentList = null;

        String[] lines = yaml.split("\\n", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex];
            String trimmed = line.trim();
            if (trimmed.isEmpty() || Strings.CS.startsWith(trimmed, "#")) {
                continue;
            }

            // List item
            if (Strings.CS.startsWith(trimmed, "- ") && currentKey != null) {
                if (currentList == null) {
                    currentList = new ArrayList<>();
                }
                currentList.add(trimmed.substring(2).trim());
                continue;
            }

            // Flush previous list
            if (currentKey != null && currentList != null) {
                result.put(currentKey, List.copyOf(currentList));
                currentList = null;
                currentKey = null;
            }

            // Key-value pair
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx > 0) {
                String key = trimmed.substring(0, colonIdx).trim();
                String value = trimmed.substring(colonIdx + 1).trim();

                if (value.isEmpty()) {
                    // Next lines might be a list
                    currentKey = key;
                } else if (isBlockScalarIndicator(value)) {
                    BlockScalar block = readBlockScalar(lines, lineIndex + 1,
                        leadingWhitespace(line), value.charAt(0));
                    result.put(key, block.value());
                    lineIndex = block.lastConsumedLine();
                    currentKey = null;
                } else if (Strings.CS.startsWith(value, "[") && Strings.CS.endsWith(value, "]")) {

                    // form, e.g. allowedTools: [Bash, Write]). Split on commas,
// matching a minimal YAML sequence — quotes/brackets inside
                    // values are not supported, matching the simple parser's scope.
                    List<String> inline = new ArrayList<>();
                    String inner = value.substring(1, value.length() - 1).trim();
                    if (!inner.isEmpty()) {
                        for (String item : inner.split(",")) {
                            String t = item.trim();
                            if (!t.isEmpty()) inline.add(t);
                        }
                    }
                    result.put(key, List.copyOf(inline));
                    currentKey = null;
                } else {
                    result.put(key, parseScalar(value));
                    // Clear currentKey so a stray "- item" after an inline value
                    // doesn't hijack this key into a list (which would silently

                    // parser would reject; Java opts to keep the string value.
                    currentKey = null;
                }
            }
        }

        // Flush trailing list
        if (currentKey != null && currentList != null) {
            result.put(currentKey, List.copyOf(currentList));
        }

        return result;
    }

    private static boolean isBlockScalarIndicator(String value) {
        return BLOCK_SCALAR_INDICATOR_PATTERN.matcher(value).matches();
    }

    private static int leadingWhitespace(String value) {
        int count = 0;
        while (count < value.length()) {
            char c = value.charAt(count);
            if (c != ' ' && c != '\t') break;
            count++;
        }
        return count;
    }

    private static BlockScalar readBlockScalar(
            String[] lines, int firstLine, int parentIndent, char style) {
        List<String> raw = new ArrayList<>();
        int last = firstLine - 1;
        int contentIndent = Integer.MAX_VALUE;
        for (int i = firstLine; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            int indent = leadingWhitespace(line);
            if (!trimmed.isEmpty() && indent <= parentIndent) break;
            raw.add(line);
            last = i;
            if (!trimmed.isEmpty()) contentIndent = Math.min(contentIndent, indent);
        }
        if (contentIndent == Integer.MAX_VALUE) return new BlockScalar("", last);

        List<String> content = new ArrayList<>(raw.size());
        for (String line : raw) {
            if (line.trim().isEmpty()) {
                content.add("");
            } else {
                content.add(line.substring(Math.min(contentIndent, line.length())));
            }
        }
        while (!content.isEmpty() && content.getLast().isEmpty()) {
            content.removeLast();
        }
        String rendered = style == '|'
            ? String.join("\n", content)
            : foldBlockScalar(content);
        return new BlockScalar(rendered, last);
    }

    private static String foldBlockScalar(List<String> lines) {
        StringBuilder out = new StringBuilder();
        boolean paragraphBreak = false;
        for (String line : lines) {
            if (line.isEmpty()) {
                paragraphBreak = true;
                continue;
            }
            if (!out.isEmpty()) out.append(paragraphBreak ? '\n' : ' ');
            out.append(line);
            paragraphBreak = false;
        }
        return out.toString();
    }

    private static Object parseScalar(String value) {
        if (value.length() >= 2 && Strings.CS.startsWith(value, "\"") && Strings.CS.endsWith(value, "\"")) {
            String inner = value.substring(1, value.length() - 1);
            StringBuilder out = new StringBuilder(inner.length());
            boolean escaped = false;
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (escaped) {
                    out.append(switch (c) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '\\', '"' -> c;
                        default -> c;
                    });
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else {
                    out.append(c);
                }
            }
            if (escaped) out.append('\\');
            return out.toString();
        }
        if (value.length() >= 2 && Strings.CS.startsWith(value, "'") && Strings.CS.endsWith(value, "'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        // YAML 1.2 booleans are the exact lower-case scalars true/false here.
        // Preserve uppercase/mixed-case values as strings so command-specific
        // parsers such as user-invocable can reject them like the the compatibility contract YAML path.
        if (Strings.CS.equals("true", value)) return Boolean.TRUE;
        if (Strings.CS.equals("false", value)) return Boolean.FALSE;
        return value;
    }

    private record BlockScalar(String value, int lastConsumedLine) {}

    /**
     * Extract a string value from metadata.
     */
    public static String getString(Map<String, Object> metadata, String key) {
        Object val = metadata.get(key);
        return val instanceof String s ? s : null;
    }

    /**
     * Extract a list of strings from metadata.
     */
    @SuppressWarnings("unchecked")
    public static List<String> getStringList(Map<String, Object> metadata, String key) {
        Object val = metadata.get(key);
        if (val instanceof List<?> list) {
            return (List<String>) list;
        }
        if (val instanceof String s) {
            return List.of(s);
        }
        return List.of();
    }

    /**
     * Parses the positive-integer frontmatter convention used by agent
     * {@code maxTurns}. Number values must already be integral; string values
     * may contain trailing text after a leading signed decimal integer.
     *
     * @return the positive value, or {@code null} when absent or invalid
     */
    public static Integer parsePositiveIntFromFrontmatter(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            if (!Double.isFinite(parsed) || parsed != Math.rint(parsed)
                    || parsed <= 0 || parsed > Integer.MAX_VALUE) {
                return null;
            }
            return (int) parsed;
        }

        Matcher matcher = INTEGER_PREFIX_PATTERN.matcher(String.valueOf(value).stripLeading());
        if (!matcher.find()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(matcher.group());
            return parsed > 0 && parsed <= Integer.MAX_VALUE ? (int) parsed : null;
        } catch (NumberFormatException _) {
            return null;
        }
    }

    public static String coerceDescriptionToString(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text.trim().isEmpty() ? null : text.trim();
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return null;
    }

    /** Only literal true or the exact string {@code "true"} is true. */
    public static boolean parseBooleanFrontmatter(Object value) {
        return Boolean.TRUE.equals(value)
            || value instanceof String text && Strings.CS.equals("true", text);
    }

    public static String parseShellFrontmatter(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return Strings.CS.equals("bash", normalized) || Strings.CS.equals("powershell", normalized) ? normalized : null;
    }

    public static List<String> splitPathInFrontmatter(Object input) {
        if (input instanceof List<?> list) {
            return list.stream().flatMap(item -> splitPathInFrontmatter(item).stream()).toList();
        }
        if (!(input instanceof String text)) return List.of();
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int braces = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') braces++;
            else if (c == '}') braces--;
            if (c == ',' && braces == 0) {
                if (!current.toString().trim().isEmpty()) parts.add(current.toString().trim());
                current.setLength(0);
            } else current.append(c);
        }
        if (!current.toString().trim().isEmpty()) parts.add(current.toString().trim());
        return parts.stream().flatMap(part -> expandBraces(part).stream()).toList();
    }

    private static List<String> expandBraces(String pattern) {
        Matcher matcher = BRACE_EXPANSION_PATTERN.matcher(pattern);
        if (!matcher.matches()) return List.of(pattern);
        List<String> result = new ArrayList<>();
        for (String alternative : matcher.group(2).split(",")) {
            result.addAll(expandBraces(matcher.group(1) + alternative.trim() + matcher.group(3)));
        }
        return result;
    }

    /**
     * Result of parsing a markdown file with frontmatter.
     */
    public record ParseResult(Map<String, Object> metadata, String body) {

        public String name() {
            return getString(metadata, "name");
        }

        public String description() {
            return coerceDescriptionToString(metadata.get("description"));
        }

        public List<String> allowedTools() {
            Object value = metadata.containsKey("allowed-tools")
                ? metadata.get("allowed-tools") : metadata.get("allowedTools");
            return getStringList(Map.of("value", value == null ? List.of() : value), "value");
        }

        public List<String> paths() {
            return splitPathInFrontmatter(metadata.get("paths"));
        }

        /**
         * Model override declared by the skill (the compatibility contract {@code model} frontmatter key,
         * e.g. {@code opus}). Null when absent — the engine then keeps its current
         * runtime model. Consumed by {@code SkillTool}'s contextModifier.
         */
        public String model() {
            return getString(metadata, "model");
        }

        /**
         * Effort override declared by the skill (the compatibility contract {@code effort} frontmatter key,
         * e.g. {@code low}/{@code medium}/{@code high}). Null when absent. Consumed
         * by {@code SkillTool}'s contextModifier.
         */
        public String effort() {
            return getString(metadata, "effort");
        }

        /**
         * Execution context declared by the skill (the compatibility contract {@code context} frontmatter
         * key, e.g. {@code fork}). Null when absent. Consumed by {@code SkillTool}
         * to decide whether the skill runs inline or in a forked sub-agent.
         */
        public String context() {
            Object v = metadata.get("context");
            return v instanceof String s ? s : null;
        }
    }
}
