package com.claudecode.tools.workflows;

import org.apache.commons.lang3.StringUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public final class WorkflowScriptParser {

/** established wire schema {@code script.maxLength}; enforced as UTF-8 bytes for files too. */
    public static final int MAX_SCRIPT_BYTES = 524_288;
    public static final String FIRST_STATEMENT_ERROR =
        "`export const meta = { name, description, phases }` must be the FIRST statement in the script";

    private WorkflowScriptParser() {}

    public static ParsedWorkflowScript parse(String script) {
        if (script == null) throw new WorkflowScriptException("Workflow script is required");
        if (script.getBytes(StandardCharsets.UTF_8).length > MAX_SCRIPT_BYTES) {
            throw new WorkflowScriptException("Workflow script exceeds the maximum size");
        }
        LiteralParser parser = new LiteralParser(script);
        parser.skipTrivia();
        if (!parser.consumeWord("export")) throw new WorkflowScriptException(FIRST_STATEMENT_ERROR);
        parser.requireTrivia();
        if (!parser.consumeWord("const")) throw new WorkflowScriptException(FIRST_STATEMENT_ERROR);
        parser.requireTrivia();
        if (!parser.consumeWord("meta")) throw new WorkflowScriptException(FIRST_STATEMENT_ERROR);
        parser.skipTrivia();
        if (!parser.consume('=')) throw new WorkflowScriptException(FIRST_STATEMENT_ERROR);
        parser.skipTrivia();
        Object value = parser.parseValue();
        if (!(value instanceof Map<?, ?> rawMeta)) {
            throw new WorkflowScriptException("Workflow meta must be an object literal");
        }
        parser.skipTrivia();
        parser.consume(';');
        int bodyStart = parser.skipTriviaAndReturnPosition();

        Map<String, Object> meta = castStringMap(rawMeta, "meta");
        String name = requiredString(meta, "name");
        String description = requiredString(meta, "description");
        String title = optionalString(meta, "title");
        String whenToUse = optionalString(meta, "whenToUse");
        List<WorkflowPhase> phases = parsePhases(meta.get("phases"));
        return new ParsedWorkflowScript(
            new WorkflowMetadata(name, title, description, whenToUse, phases),
            script.substring(bodyStart).stripLeading());
    }

    private static List<WorkflowPhase> parsePhases(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            throw new WorkflowScriptException("Workflow meta.phases must be an array");
        }
        List<WorkflowPhase> phases = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> raw)) {
                throw new WorkflowScriptException("Workflow phase " + (i + 1) + " must be an object");
            }
            Map<String, Object> phase = castStringMap(raw, "phase");
            phases.add(new WorkflowPhase(
                requiredString(phase, "title"),
                optionalString(phase, "detail"),
                optionalString(phase, "model")));
        }
        return List.copyOf(phases);
    }

    private static Map<String, Object> castStringMap(Map<?, ?> value, String label) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new WorkflowScriptException("Workflow " + label + " keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String requiredString(Map<String, Object> value, String key) {
        String result = optionalString(value, key);
        if (StringUtils.isBlank(result)) {
            throw new WorkflowScriptException("Workflow meta." + key + " must be a non-empty string");
        }
        return result;
    }

    private static String optionalString(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (raw == null) return null;
        if (!(raw instanceof String text)) {
            throw new WorkflowScriptException("Workflow meta." + key + " must be a string");
        }
        return text;
    }

    private static final class LiteralParser {
        private static final List<String> RESERVED_KEYS =
            List.of("__proto__", "constructor", "prototype");

        private final String source;
        private int position;

        private LiteralParser(String source) {
            this.source = source;
        }

        private Object parseValue() {
            skipTrivia();
            if (position >= source.length()) fail("Unexpected end of workflow metadata");
            char c = source.charAt(position);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '\'', '"', '`' -> parseString();
                case '-' -> parseNegativeNumber();
                default -> {
                    if (Character.isDigit(c)) yield parseNumber();
                    if (consumeWord("true")) yield Boolean.TRUE;
                    if (consumeWord("false")) yield Boolean.FALSE;
                    if (consumeWord("null")) yield null;
                    fail("Workflow metadata must contain only literal values");
                    yield null;
                }
            };
        }

        private Map<String, Object> parseObject() {
            consume('{');
            skipTrivia();
            Map<String, Object> result = new LinkedHashMap<>();
            if (consume('}')) return result;
            while (true) {
                skipTrivia();
                if (peek("...")) fail("Spread properties are unavailable in workflow metadata");
                if (peek("[")) fail("Computed properties are unavailable in workflow metadata");
                String key = parsePropertyKey();
                if (RESERVED_KEYS.contains(key)) {
                    fail("Reserved workflow metadata key: " + key);
                }
                skipTrivia();
                if (!consume(':')) fail("Expected ':' after workflow metadata key");
                Object value = parseValue();
                result.put(key, value);
                skipTrivia();
                if (consume('}')) return result;
                if (!consume(',')) fail("Expected ',' or '}' in workflow metadata");
                skipTrivia();
                if (consume('}')) return result;
            }
        }

        private List<Object> parseArray() {
            consume('[');
            skipTrivia();
            List<Object> result = new ArrayList<>();
            if (consume(']')) return result;
            while (true) {
                skipTrivia();
                if (peek("...")) fail("Spread elements are unavailable in workflow metadata");
                if (peek(",")) fail("Array holes are unavailable in workflow metadata");
                result.add(parseValue());
                skipTrivia();
                if (consume(']')) return result;
                if (!consume(',')) fail("Expected ',' or ']' in workflow metadata");
                skipTrivia();
                if (consume(']')) return result;
            }
        }

        private String parsePropertyKey() {
            if (position >= source.length()) fail("Unexpected end of workflow metadata object");
            char c = source.charAt(position);
            if (c == '\'' || c == '"' || c == '`') return parseString();
            int start = position;
            if (!Character.isJavaIdentifierStart(c) && c != '$') {
                fail("Workflow metadata object keys must be identifiers or strings");
            }
            position++;
            while (position < source.length()) {
                char next = source.charAt(position);
                if (!Character.isJavaIdentifierPart(next) && next != '$') break;
                position++;
            }
            return source.substring(start, position);
        }

        private String parseString() {
            char quote = source.charAt(position++);
            StringBuilder out = new StringBuilder();
            while (position < source.length()) {
                char c = source.charAt(position++);
                if (c == quote) return out.toString();
                if (quote == '`' && c == '$' && position < source.length()
                        && source.charAt(position) == '{') {
                    fail("Template interpolation is unavailable in workflow metadata");
                }
                if (c == '\\') {
                    if (position >= source.length()) fail("Unterminated escape in workflow metadata");
                    char escaped = source.charAt(position++);
                    out.append(switch (escaped) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'v' -> 0x0b;
                        case '0' -> '\0';
                        case '\\' -> '\\';
                        case '\'' -> '\'';
                        case '"' -> '"';
                        case '`' -> '`';
                        default -> escaped;
                    });
                } else {
                    if ((quote == '\'' || quote == '"') && (c == '\n' || c == '\r')) {
                        fail("Unterminated string in workflow metadata");
                    }
                    out.append(c);
                }
            }
            fail("Unterminated string in workflow metadata");
            return "";
        }

        private Number parseNegativeNumber() {
            position++;
            if (position >= source.length() || !Character.isDigit(source.charAt(position))) {
                fail("Unary '-' in workflow metadata is only allowed for numbers");
            }
            Number value = parseNumber();
            return value instanceof Long integer ? -integer : -value.doubleValue();
        }

        private Number parseNumber() {
            int start = position;
            while (position < source.length() && Character.isDigit(source.charAt(position))) position++;
            boolean decimal = false;
            if (position < source.length() && source.charAt(position) == '.') {
                decimal = true;
              do {
                position++;
              }
              while (position < source.length() && Character.isDigit(source.charAt(position)));
            }
            if (position < source.length()
                    && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
                decimal = true;
                position++;
                if (position < source.length()
                        && (source.charAt(position) == '+' || source.charAt(position) == '-')) position++;
                int exponentStart = position;
                while (position < source.length() && Character.isDigit(source.charAt(position))) position++;
                if (exponentStart == position) fail("Invalid number in workflow metadata");
            }
            String raw = source.substring(start, position);
            try {
                return decimal ? Double.parseDouble(raw) : Long.parseLong(raw);
            } catch (NumberFormatException _) {
                fail("Invalid number in workflow metadata");
                return 0;
            }
        }

        private void skipTrivia() {
            while (position < source.length()) {
                char c = source.charAt(position);
                if (Character.isWhitespace(c)) {
                    position++;
                    continue;
                }
                if (peek("//")) {
                    position += 2;
                    while (position < source.length() && source.charAt(position) != '\n') position++;
                    continue;
                }
                if (peek("/*")) {
                    int end = source.indexOf("*/", position + 2);
                    if (end < 0) fail("Unterminated comment in workflow script");
                    position = end + 2;
                    continue;
                }
                break;
            }
        }

        private int skipTriviaAndReturnPosition() {
            skipTrivia();
            return position;
        }

        private void requireTrivia() {
            int before = position;
            skipTrivia();
            if (before == position) throw new WorkflowScriptException(FIRST_STATEMENT_ERROR);
        }

        private boolean consumeWord(String word) {
            if (!source.regionMatches(position, word, 0, word.length())) return false;
            int end = position + word.length();
            if (end < source.length()) {
                char next = source.charAt(end);
                if (Character.isJavaIdentifierPart(next) || next == '$') return false;
            }
            position = end;
            return true;
        }

        private boolean consume(char expected) {
            if (position < source.length() && source.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private boolean peek(String value) {
            return source.startsWith(value, position);
        }

        private void fail(String message) {
            throw new WorkflowScriptException(message + " at character " + position);
        }
    }
}
