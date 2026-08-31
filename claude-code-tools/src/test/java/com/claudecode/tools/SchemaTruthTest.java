package com.claudecode.tools;

import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;


class SchemaTruthTest {

    private static final Path TS_ROOT = referenceSourceRoot();

    private static Path referenceSourceRoot() {
        String configured = System.getenv("CLAUDE_CODE_REFERENCE_SOURCE_ROOT");
        return StringUtils.isBlank(configured)
            ? Path.of(System.getProperty("user.home"), "claude-code-reference")
            : Path.of(configured);
    }


    private static final Map<String, String> TOOL_TS_DIR = Map.of(
            "Bash", "BashTool",
            "Read", "FileReadTool",
            "Write", "FileWriteTool",
            "Edit", "FileEditTool",
            "NotebookEdit", "NotebookEditTool",
            "WebFetch", "WebFetchTool",
            "WebSearch", "WebSearchTool",
            "Agent", "AgentTool",
            "AskUserQuestion", "AskUserQuestionTool",
            "SendMessage", "SendMessageTool");

    private static final Pattern Z_ENUM = Pattern.compile("z\\.enum\\(\\s*\\[(.*?)\\]\\s*\\)", Pattern.DOTALL);
    private static final Pattern STR_LITERAL = Pattern.compile("'([^']*)'|\"([^\"]*)\"");
    // Top-level property: name at line start, followed by ':'.
    private static final Pattern PROP = Pattern.compile("(?m)^(\\s*)(\\w+)\\s*:");

    @Test
    void javaSchemaMatchesTsTruth() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(TS_ROOT),
                "TS repo not present at " + TS_ROOT + " — skipping schema-truth check");

        ToolRegistry registry = ToolBootstrap.buildBuiltInRegistry();
        List<String> drifts = new ArrayList<>();

        for (Tool<?, ?> tool : registry.getAll()) {
            String tsDirName = TOOL_TS_DIR.get(tool.name());
            if (tsDirName == null) continue;
            Path tsDir = TS_ROOT.resolve("src/tools").resolve(tsDirName);
            if (!Files.isDirectory(tsDir)) continue;

            String region = inputSchemaRegion(tsDir);
            if (region == null) continue; // indirection / unparseable

            List<Set<String>> tsEnums = parseTsEnums(region);
            Map<String, TsProp> tsProps = parseTsProps(region);
            JavaSchema java = collectJavaSchema(tool.inputSchema());

            // (1) enum values
            for (Set<String> ts : tsEnums) {
                boolean covered = java.enums.stream().anyMatch(j -> j.containsAll(ts));
                if (!covered) {
                    drifts.add(tool.name() + ": TS enum " + ts
                            + " not represented in Java schema (value dropped / narrowed?)");
                }
            }

            // (2) required + (3) formats + field presence
            for (var e : tsProps.entrySet()) {
                String name = e.getKey();
                TsProp ts = e.getValue();
                JavaProp jp = java.props.get(name);

                if (jp == null) {
                    drifts.add(tool.name() + ": TS input field '" + name
                            + "' is missing entirely from the Java schema");
                    continue;
                }
                if (!ts.optional && !jp.required) {
                    drifts.add(tool.name() + ": TS field '" + name
                            + "' is required but missing from Java 'required'");
                }
                if (ts.optional && jp.required) {
                    drifts.add(tool.name() + ": TS field '" + name
                            + "' is optional but Java marks it required (over-strict)");
                }
                for (String fmt : ts.formats) {
                    if (!jp.formats.contains(fmt)) {
                        drifts.add(tool.name() + ": TS validates '" + name
                                + "' with a string format but Java schema has no matching "
                                + fmt + " constraint");
                    }
                }
            }
        }

        if (!drifts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Java tool schemas diverge from TS originals:\n");
            for (String d : drifts) sb.append("  - ").append(d).append("\n");
            System.out.println("[SCHEMA_TRUTH_SUMMARY]\n" + sb);
            fail(sb.toString());
        }
    }



    private String inputSchemaRegion(Path tsDir) throws IOException {
        try (var stream = Files.walk(tsDir)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return Strings.CS.endsWith(n, ".ts") || Strings.CS.endsWith(n, ".tsx");
                    })::iterator) {
                String content = Files.readString(file);
                int idx = content.indexOf("inputSchema");
                while (idx >= 0) {
                    int j = idx + "inputSchema".length();
                    while (j < content.length() && Character.isWhitespace(content.charAt(j))) j++;
                    if (j < content.length() && content.charAt(j) == '=') {
                        int lazy = content.indexOf("lazySchema", j);
                        int obj = indexOfAny(content,
                                new String[]{"z.object(", "z.strictObject("}, j);
                        if (obj >= 0) {
// If wrapped in lazySchema( =>...), the z.object must be
                            // the immediate return (before lazySchema's closing ')');
// an indirection (fullInputSchema) is skipped.
                            if (lazy >= 0 && lazy < obj) {
                                int lparen = content.indexOf('(', lazy);
                                int close = matchParen(content, lparen);
                                if (obj > close) {
                                    idx = content.indexOf("inputSchema", idx + 1);
                                    continue;
                                }
                            }
                            int brace = content.indexOf('{', obj);
                            if (brace >= 0) {
                                int end = matchBrace(content, brace);
                                if (end >= 0) return content.substring(brace, end + 1);
                            }
                        }
                    }
                    idx = content.indexOf("inputSchema", idx + 1);
                }
            }
        }
        return null;
    }

    private List<Set<String>> parseTsEnums(String region) {
        List<Set<String>> result = new ArrayList<>();
        Matcher m = Z_ENUM.matcher(region);
        while (m.find()) {
            Set<String> values = new HashSet<>();
            Matcher lit = STR_LITERAL.matcher(m.group(1));
            while (lit.find()) {
                values.add(lit.group(1) != null ? lit.group(1) : lit.group(2));
            }
            if (!values.isEmpty()) result.add(values);
        }
        return result;
    }

    // Indentation-based top-level property extraction (robust against template
    // literals like `${...}` that would corrupt brace counting).
    private Map<String, TsProp> parseTsProps(String region) {
        Map<String, TsProp> props = new LinkedHashMap<>();
        Matcher m = PROP.matcher(region);
        List<int[]> found = new ArrayList<>(); // {wsStart, nameStart, colonIdx}
        while (m.find()) {
            int wsStart = m.start(1);
            int nameStart = m.start(2);
            int colon = region.indexOf(':', nameStart);
            if (colon >= 0) found.add(new int[]{wsStart, nameStart, colon});
        }
        if (found.isEmpty()) return props;
        int topIndent = found.getFirst()[1] - found.getFirst()[0];

        List<Integer> topIdx = new ArrayList<>();
        for (int k = 0; k < found.size(); k++) {
            if (found.get(k)[1] - found.get(k)[0] == topIndent) topIdx.add(k);
        }
        for (int t = 0; t < topIdx.size(); t++) {
            int k = topIdx.get(t);
            int[] f = found.get(k);
            String name = region.substring(f[1], f[2]);
            int valueStart = f[2] + 1;
            int valueEnd = (t + 1 < topIdx.size())
                    ? found.get(topIdx.get(t + 1))[1] : region.length();
            props.put(name, analyzeValue(region.substring(valueStart, valueEnd)));
        }
        return props;
    }

    private TsProp analyzeValue(String value) {
// optional/default may be wrapped (e.g. semanticNumber(z.number.optional)),
        // and formats may be chained; scan as substrings (zod markers are unambiguous).
        boolean optional = Strings.CS.contains(value, ".optional(") || Strings.CS.contains(value, ".default(");
        Set<String> formats = new HashSet<>();
        if (Strings.CS.contains(value, ".email(")) formats.add("email");
        if (Strings.CS.contains(value, ".url(")) formats.add("uri");
        if (Strings.CS.contains(value, ".uuid(")) formats.add("uuid");
        if (Strings.CS.contains(value, ".datetime(")) formats.add("date-time");
        if (Strings.CS.contains(value, ".regex(")) formats.add("pattern");
        return new TsProp(optional, formats);
    }

    private int indexOfAny(String s, String[] needles, int from) {
        int best = -1;
        for (String n : needles) {
            int i = s.indexOf(n, from);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ---- Java schema extraction ------------------------------------------

    private JavaSchema collectJavaSchema(JsonNode schema) {
        JavaSchema out = new JavaSchema();
        if (schema == null || !schema.isObject()) return out;
        JsonNode props = schema.get("properties");
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode r : required) out.required.add(r.asText());
        }
        if (props != null && props.isObject()) {
            var it = props.fields();
            while (it.hasNext()) {
                var e = it.next();
                String name = e.getKey();
                JsonNode node = e.getValue();
                Set<String> formats = new HashSet<>();
                if (node != null && node.isObject()) {
                    JsonNode f = node.get("format");
                    if (f != null && f.isTextual()) formats.add(f.asText());
                    if (node.has("pattern")) formats.add("pattern");
                    if (node.has("enum")) {
                        Set<String> vals = new HashSet<>();
                        for (JsonNode v : node.get("enum")) if (v.isTextual()) vals.add(v.asText());
                        if (!vals.isEmpty()) out.enums.add(vals);
                    }
                }
                out.props.put(name, new JavaProp(out.required.contains(name), formats));
            }
        }
        return out;
    }

    // ---- data holders -----------------------------------------------------

    private static final class TsProp {
        final boolean optional;
        final Set<String> formats;
        TsProp(boolean optional, Set<String> formats) {
            this.optional = optional;
            this.formats = formats;
        }
    }

    private static final class JavaProp {
        final boolean required;
        final Set<String> formats;
        JavaProp(boolean required, Set<String> formats) {
            this.required = required;
            this.formats = formats;
        }
    }

    private static final class JavaSchema {
        final Set<String> required = new HashSet<>();
        final List<Set<String>> enums = new ArrayList<>();
        final Map<String, JavaProp> props = new LinkedHashMap<>();
    }
}
