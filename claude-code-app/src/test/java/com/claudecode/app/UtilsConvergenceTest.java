package com.claudecode.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/**
 * Executable recognition and anti-regression guard for shared {@code *Utils} convergence across all
 * product modules.
 *
 * <ul>
 *   <li><b>Core Utils Shadowing Guard</b>: dynamically indexes {@code public static} methods on
 *       {@code claude-code-core} utility types and rejects non-delegating {@code static} helpers in
 *       other classes that shadow those utility names unless explicitly documented as semantically
 *       distinct.</li>
 *   <li><b>Cross-Class {@code private static} Clone Detector</b>: token-normalizes every
 *       {@code private static} helper body (erasing comments, parameter names, and local variable
 *       identifiers) and fails when an identical non-trivial helper implementation is copy-pasted
 *       across 3 or more production classes.</li>
 *   <li><b>Commons Lang3 Helper Guard</b>: rejects hand-rolled {@code firstNonBlank} static helpers
 *       in favor of {@link StringUtils#firstNonBlank(CharSequence...)}.</li>
 * </ul>
 */
class UtilsConvergenceTest {

    private static final Pattern SETTINGS_MODULE = Pattern.compile(
        "^\\s*\"(claude-code-[a-z0-9-]+)\"", Pattern.MULTILINE);

    private static final Pattern PUBLIC_STATIC_METHOD = Pattern.compile(
        "\\bpublic\\s+static\\s+(?:<[^{;]+?>\\s+)?[A-Za-z0-9_.<>\\[\\], ?]+?\\s+"
            + "([a-z][A-Za-z0-9_]*)\\s*\\(");

    private static final Pattern ANY_STATIC_METHOD = Pattern.compile(
        "(?m)^[ \\t]*(?:(private|protected|public)\\s+)?static\\s+"
            + "(?:<[^{;\\n]+?>\\s+)?([A-Za-z0-9_.<>\\[\\], ?]+?)\\s+"
            + "([a-z][A-Za-z0-9_]*)\\s*\\(([^)\\n]*)\\)\\s*(?:throws\\s+[A-Za-z0-9_.,\\s]+)?\\{");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
        "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[A-Za-z_]\\w*|\\d+|[^\\s\\w]");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_]\\w*$");
    private static final Pattern LOWER_IDENT = Pattern.compile("^[a-z]\\w*$");
    private static final Pattern TYPE_LIKE = Pattern.compile(
        "^(?:[A-Z]\\w*|int|long|boolean|double|float|char|byte|short|var)$");

    /**
     * Generic factory / codec / relational method names legitimately defined per domain value type
     * (e.g. {@code SandboxConfig.fromJson}, {@code CronSchedule.isValid}, {@code SemverUtils.gt}).
     */
    private static final Set<String> GENERIC_DOMAIN_VERBS = Set.of(
        "of", "from", "parse", "toJson", "fromJson", "validate", "isValid",
        "exists", "message", "order", "gt", "gte", "lt", "lte", "wrap");

    /**
     * Verified same-name helpers whose behavioral contract intentionally diverges from the
     * homonymous {@code claude-code-core} utility method. Every entry must carry the verified
     * root-cause reason from investigation.
     */
    private static final Map<String, String> DOCUMENTED_DIVERGENCES = Map.ofEntries(
        Map.entry(
            "claude-code-http/src/main/java/com/claudecode/http/HttpCalls.java#closeQuietly",
            "claude-code-http is a zero-dependency transport leaf (ModuleArchitectureTest forbids "
                + "importing claude-code-core)."),
        Map.entry(
            "claude-code-core/src/main/java/com/claudecode/core/message/AttachmentRenderer.java#addLineNumbers",
            "Splits strictly on LF and renders an empty body as '1\\t' for attachment blocks, "
                + "whereas FileTextUtils.addLineNumbers returns '' on empty input and splits CRLF."),
        Map.entry(
            "claude-code-core/src/main/java/com/claudecode/core/metrics/SessionMetricsFormat.java#formatTokens",
            "Uses uppercase K/M suffixes and integer rounding >= 100 for session metric badges, "
                + "whereas FormatUtils.formatTokens uses lowercase CompactNumberFormat."),
        Map.entry(
            "claude-code-core/src/main/java/com/claudecode/core/metrics/SessionMetricsFormat.java#formatDuration",
            "Formats sub-minute durations with tenths ('12.3s') and compact '1m23s' without spaces, "
                + "whereas FormatUtils.formatDuration formats whole seconds ('12s') and '1m 23s'."),
        Map.entry(
            "claude-code-services/src/main/java/com/claudecode/services/config/SettingsTreeReader.java#readJson",
            "Strips UTF-8 BOM and treats blank settings files as '{}' per settings-tree contract, "
                + "whereas JsonUtils.readJson parses strict non-empty JSON."),
        Map.entry(
            "claude-code-ui/src/main/java/com/claudecode/ui/MarkdownRenderer.java#wrapText",
            "Parses ANSI SGR runs and re-opens active styles on each wrapped Markdown table row, "
                + "whereas FormatUtils.wrapText operates on unstyled plain text."),
        Map.entry(
            "claude-code-ui/src/main/java/com/claudecode/ui/lanterna/transcript/MessagePanel.java#truncateToLines",
            "Word-wraps to terminal column width and shrinks grapheme-by-grapheme so the ellipsis "
                + "fits within maxLines, whereas StringUtils.truncateToLines splits only on '\\n'."),
        Map.entry(
            "claude-code-ui/src/main/java/com/claudecode/ui/lanterna/transcript/ShellOutputTruncator.java#truncate",
            "Domain entry point returning a multi-row TruncatedOutput record for shell transcripts, "
                + "not a single-string column truncator.")
    );

    private static final int MIN_CLONE_TOKENS = 16;
    private static final int MAX_ALLOWED_DUPLICATE_FILES = 2;

    @Test
    void productionClassesDoNotShadowCoreUtilsMethods() throws IOException {
        Path root = repositoryRoot();
        Map<String, Set<String>> coreUtilMethods = discoverCoreUtilMethods(root);
        Set<Path> coreUtilFiles = discoverCoreUtilFiles(root);

        List<String> offenders = new ArrayList<>();
        for (Path file : allProductionJavaFiles(root)) {
            if (coreUtilFiles.contains(file)) {
                continue;
            }
            String rel = root.relativize(file).toString().replace('\\', '/');
            String source = read(file);
            for (StaticMethod method : extractStaticMethods(source)) {
                Set<String> owners = coreUtilMethods.get(method.name());
                if (owners == null) {
                    continue;
                }
                String key = rel + "#" + method.name();
                if (DOCUMENTED_DIVERGENCES.containsKey(key)) {
                    continue;
                }
                if (delegatesToAnyOwner(method.body(), owners, method.name())) {
                    continue;
                }
                offenders.add(rel + ":" + method.line() + " defines static `" + method.name()
                    + "(" + method.params() + ")` shadowing " + owners + "#" + method.name());
            }
        }
        assertTrue(offenders.isEmpty(), () ->
            "Production classes must reuse claude-code-core Utils (or document verified semantic "
                + "divergence in DOCUMENTED_DIVERGENCES):\n  " + String.join("\n  ", offenders));
    }

    @Test
    void privateStaticHelpersAreNotClonedAcrossClasses() throws IOException {
        Path root = repositoryRoot();
        Map<String, List<MethodOccurrence>> byNormalizedBody = new LinkedHashMap<>();

        for (Path file : allProductionJavaFiles(root)) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String source = read(file);
            for (StaticMethod method : extractStaticMethods(source)) {
                if (!Strings.CS.equals("private", method.visibility())) {
                    continue;
                }
                NormalizedBody normalized = normalizeMethodBody(method.params(), method.body());
                if (normalized.tokenCount() < MIN_CLONE_TOKENS) {
                    continue;
                }
                byNormalizedBody
                    .computeIfAbsent(normalized.signature(), _ -> new ArrayList<>())
                    .add(new MethodOccurrence(
                        rel, method.line(), method.returnType(), method.name(),
                        method.params(), normalized.tokenCount()));
            }
        }

        List<String> offenders = new ArrayList<>();
        byNormalizedBody.entrySet().stream()
            .sorted(Comparator.comparingInt(
                (Map.Entry<String, List<MethodOccurrence>> e) -> distinctFileCount(e.getValue()))
                .reversed())
            .forEach(entry -> {
                List<MethodOccurrence> occurrences = entry.getValue();
                int fileCount = distinctFileCount(occurrences);
                if (fileCount > MAX_ALLOWED_DUPLICATE_FILES) {
                    MethodOccurrence sample = occurrences.getFirst();
                    StringBuilder detail = new StringBuilder();
                    detail.append("[").append(fileCount).append(" files, ")
                        .append(sample.tokenCount()).append(" tokens] `")
                        .append(sample.returnType()).append(' ').append(sample.name())
                        .append('(').append(sample.params()).append(")`:");
                    for (MethodOccurrence occ : occurrences) {
                        detail.append("\n      - ").append(occ.relPath()).append(':')
                            .append(occ.line()).append(" -> ").append(occ.name())
                            .append('(').append(occ.params()).append(')');
                    }
                    offenders.add(detail.toString());
                }
            });

        assertTrue(offenders.isEmpty(), () ->
            "Normalized `private static` helpers duplicated across 3+ classes must be consolidated "
                + "into a shared utility:\n  " + String.join("\n  ", offenders));
    }

    @Test
    void standardCommonsLangFirstNonBlankIsNotReimplemented() throws IOException {
        Path root = repositoryRoot();
        List<String> offenders = new ArrayList<>();
        for (Path file : allProductionJavaFiles(root)) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String source = read(file);
            for (StaticMethod method : extractStaticMethods(source)) {
                if (Strings.CS.equals("firstNonBlank", method.name())) {
                    offenders.add(rel + ":" + method.line() + " defines `" + method.name()
                        + "(" + method.params() + ")`; use org.apache.commons.lang3.StringUtils.firstNonBlank");
                }
            }
        }
        assertTrue(offenders.isEmpty(), () ->
            "Use Commons Lang3 StringUtils.firstNonBlank instead of custom helpers:\n  "
                + String.join("\n  ", offenders));
    }

    private static Set<Path> discoverCoreUtilFiles(Path root) throws IOException {
        Path coreMain = root.resolve("claude-code-core/src/main/java");
        Set<Path> files = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(coreMain)) {
            walk.filter(p -> Strings.CS.endsWith(p.toString(), ".java"))
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return Strings.CS.contains(name, "Util")
                        || Strings.CS.equals("Platform.java", name);
                })
                .sorted()
                .forEach(files::add);
        }
        return Set.copyOf(files);
    }

    private static Map<String, Set<String>> discoverCoreUtilMethods(Path root) throws IOException {
        Map<String, Set<String>> methods = new LinkedHashMap<>();
        for (Path file : discoverCoreUtilFiles(root)) {
            String fileName = file.getFileName().toString();
            String owner = fileName.substring(0, fileName.length() - ".java".length());
            String source = stripComments(read(file));
            Matcher matcher = PUBLIC_STATIC_METHOD.matcher(source);
            while (matcher.find()) {
                String methodName = matcher.group(1);
                if (GENERIC_DOMAIN_VERBS.contains(methodName)) {
                    continue;
                }
                methods.computeIfAbsent(methodName, _ -> new LinkedHashSet<>()).add(owner);
            }
        }
        return methods;
    }

    private static List<Path> allProductionJavaFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Matcher matcher = SETTINGS_MODULE.matcher(read(root.resolve("settings.gradle.kts")));
        while (matcher.find()) {
            Path mainJava = root.resolve(matcher.group(1)).resolve("src/main/java");
            if (!Files.isDirectory(mainJava)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(mainJava)) {
                walk.filter(p -> Strings.CS.endsWith(p.toString(), ".java"))
                    .sorted()
                    .forEach(files::add);
            }
        }
        return List.copyOf(files);
    }

    private static List<StaticMethod> extractStaticMethods(String source) {
        List<StaticMethod> result = new ArrayList<>();
        Matcher matcher = ANY_STATIC_METHOD.matcher(source);
        while (matcher.find()) {
            String visibility = matcher.group(1) == null ? "package" : matcher.group(1);
            String returnType = matcher.group(2).trim();
            String name = matcher.group(3);
            String params = matcher.group(4).trim();
            int openBrace = matcher.end() - 1;
            int closeBrace = findMatchingBrace(source, openBrace);
            if (closeBrace < 0) {
                continue;
            }
            int line = countNewlinesBefore(source, matcher.start()) + 1;
            result.add(new StaticMethod(
                line, visibility, returnType, name, params,
                source.substring(openBrace, closeBrace + 1)));
        }
        return result;
    }

    private static int findMatchingBrace(String source, int startBrace) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = startBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (escaped) {
                escaped = false;
                continue;
            }
            if ((inString || inChar) && c == '\\') {
                escaped = true;
                continue;
            }
            if (!inChar && c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString && c == '\'') {
                inChar = !inChar;
                continue;
            }
            if (inString || inChar) {
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean delegatesToAnyOwner(String body, Set<String> owners, String methodName) {
        String clean = stripComments(body);
        for (String owner : owners) {
            if (Strings.CS.contains(clean, owner + "." + methodName + "(")) {
                return true;
            }
        }
        return false;
    }

    private static NormalizedBody normalizeMethodBody(String params, String body) {
        String clean = stripComments(body);
        Map<String, String> identifierAliases = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(params)) {
            int idx = 0;
            for (String rawParam : params.split(",")) {
                String trimmed = rawParam.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                String paramName = parts[parts.length - 1];
                while (Strings.CS.startsWith(paramName, ".")) {
                    paramName = paramName.substring(1);
                }
                if (IDENTIFIER.matcher(paramName).matches()) {
                    identifierAliases.put(paramName, "$P" + idx++);
                }
            }
        }

        List<String> rawTokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(clean);
        while (matcher.find()) {
            rawTokens.add(matcher.group());
        }

        List<String> normalized = new ArrayList<>(rawTokens.size());
        for (int i = 0; i < rawTokens.size(); i++) {
            String tok = rawTokens.get(i);
            String mapped = identifierAliases.get(tok);
            if (mapped != null) {
                normalized.add(mapped);
                continue;
            }
            if (i >= 1 && i + 1 < rawTokens.size()
                    && LOWER_IDENT.matcher(tok).matches()
                    && isVarTerminator(rawTokens.get(i + 1))
                    && TYPE_LIKE.matcher(rawTokens.get(i - 1)).matches()) {
                String localAlias = "$V" + identifierAliases.size();
                identifierAliases.put(tok, localAlias);
                normalized.add(localAlias);
                continue;
            }
            normalized.add(tok);
        }
        return new NormalizedBody(String.join(" ", normalized), normalized.size());
    }

    private static boolean isVarTerminator(String token) {
        return Strings.CS.equals("=", token)
            || Strings.CS.equals(":", token)
            || Strings.CS.equals(";", token);
    }

    private static int distinctFileCount(List<MethodOccurrence> occurrences) {
        Set<String> files = new LinkedHashSet<>();
        for (MethodOccurrence occ : occurrences) {
            files.add(occ.relPath());
        }
        return files.size();
    }

    private static String stripComments(String source) {
        String noBlock = BLOCK_COMMENT.matcher(source).replaceAll("");
        return LINE_COMMENT.matcher(noBlock).replaceAll("");
    }

    private static int countNewlinesBefore(String source, int index) {
        int count = 0;
        for (int i = 0; i < index; i++) {
            if (source.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(path.toString(), e);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))
                    && Files.isDirectory(current.resolve("claude-code-core"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate repository root");
    }

    private record StaticMethod(
        int line,
        String visibility,
        String returnType,
        String name,
        String params,
        String body) {}

    private record NormalizedBody(String signature, int tokenCount) {}

    private record MethodOccurrence(
        String relPath,
        int line,
        String returnType,
        String name,
        String params,
        int tokenCount) {}
}
