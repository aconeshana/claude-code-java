package com.claudecode.ui.syntax;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Wraps a single shared {@link Registry} with TextMate grammars from {@code
 * src/main/resources/grammars/}.
 */
public final class TmTokenizer {

    private static final Logger LOG = LoggerFactory.getLogger(TmTokenizer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String GRAMMAR_ROOT = "/grammars/";
    private static final String INDEX_RESOURCE = GRAMMAR_ROOT + "index.json";

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initialized = false;
    private static volatile Registry registry;

    private static final Map<String, GrammarEntry> ENTRIES = new HashMap<>();
    /** scope name → canonical entry, used to resolve cross-grammar includes lazily. */
    private static final Map<String, GrammarEntry> ENTRIES_BY_SCOPE = new HashMap<>();
    /** Loaded grammars keyed by their scope name. Lazy-populated on first use. */
    private static final Map<String, IGrammar> GRAMMARS = new HashMap<>();
    /** Resource file → external scope references discovered from its JSON tree. */
    private static final Map<String, Set<String>> GRAMMAR_DEPENDENCIES = new HashMap<>();

    private TmTokenizer() {}

    /** Tokenized output for one source line. */
    public record TmToken(int start, int end, List<String> scopes) {}

    /** Result of tokenizing a code block: lines × tokens. */
    public record TokenizedCode(List<List<TmToken>> lines) {
        public boolean isEmpty() { return lines.isEmpty(); }
    }

/** Index entry — kept private; {@link #knownLanguages} exposes alias set only. */
    private record GrammarEntry(String file, String scope) {}

    /** Returns the set of supported language aliases (e.g. {@code "py"}, {@code "rust"}). */
    public static Set<String> knownLanguages() {
        ensureInitialized();
        return Collections.unmodifiableSet(ENTRIES.keySet());
    }

    /** Returns true if {@code lang} is a known alias. Null/empty → false. */
    public static boolean isSupported(String lang) {
        if (StringUtils.isBlank(lang)) return false;
        ensureInitialized();
        return ENTRIES.containsKey(lang.toLowerCase(Locale.ROOT));
    }

    /**
     * Tokenize {@code code} as language {@code lang}. Returns {@code null}
     * when the language isn't registered (caller should fall back to plain
     * text); never throws on grammar loading errors — logs and returns null.
     */
    public static TokenizedCode tokenize(String code, String lang) {
        if (StringUtils.isEmpty(code) || lang == null) return null;
        ensureInitialized();
        GrammarEntry entry = ENTRIES.get(lang.toLowerCase(Locale.ROOT));
        if (entry == null) return null;
        IGrammar grammar;
        try {
            grammar = grammarFor(entry);
        } catch (Exception e) {
            LOG.warn("Failed to load TM grammar '{}': {}", entry.file, e.toString());
            return null;
        }
        if (grammar == null) return null;

        String[] sourceLines = code.split("\n", -1);
        ArrayList<List<TmToken>> result = new ArrayList<>(sourceLines.length);
        synchronized (grammar) {
            IStateStack state = null;
            for (String line : sourceLines) {
                try {
                    ITokenizeLineResult<IToken[]> tokenized =
                        grammar.tokenizeLine(line, state, TIMEOUT);
                    IToken[] tokens = tokenized.getTokens();
                    ArrayList<TmToken> lineTokens = new ArrayList<>(tokens.length);
                    for (IToken t : tokens) {
                        lineTokens.add(new TmToken(t.getStartIndex(), t.getEndIndex(), t.getScopes()));
                    }
                    result.add(lineTokens);
                    state = tokenized.getRuleStack();
                } catch (Exception _) {
                    // Bad line — emit a single un-scoped token spanning the
                    // whole line so output stays line-aligned.
                    result.add(List.of(new TmToken(0, line.length(), List.of())));
                }
            }
        }
        return new TokenizedCode(result);
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private static IGrammar grammarFor(GrammarEntry entry) {
        synchronized (INIT_LOCK) {
            return loadGrammarGraph(entry, new HashSet<>());
        }
    }

    private static IGrammar loadGrammarGraph(GrammarEntry entry, Set<String> loadingScopes) {
        IGrammar loaded = GRAMMARS.get(entry.scope);
        if (loaded != null) return loaded;
        if (!loadingScopes.add(entry.scope)) return null;
        try {
            for (String dependencyScope : dependenciesOf(entry)) {
                GrammarEntry dependency = ENTRIES_BY_SCOPE.get(dependencyScope);
                if (dependency != null) loadGrammarGraph(dependency, loadingScopes);
            }
            IGrammar grammar = loadGrammar(entry);
            if (grammar != null) GRAMMARS.put(entry.scope, grammar);
            return grammar;
        } finally {
            loadingScopes.remove(entry.scope);
        }
    }

    private static IGrammar loadGrammar(GrammarEntry entry) {
        IGrammarSource src = IGrammarSource.fromResource(TmTokenizer.class, GRAMMAR_ROOT + entry.file);
        try {
            return registry.addGrammar(src);
        } catch (Exception e) {
            LOG.warn("addGrammar('{}') failed: {}", entry.file, e.toString());
            return null;
        }
    }


    private static void ensureInitialized() {
        if (initialized) return;
        synchronized (INIT_LOCK) {
            if (initialized) return;
            registry = new Registry();
            loadIndex();
            initialized = true;
        }
    }

    private static Set<String> dependenciesOf(GrammarEntry entry) {
        return GRAMMAR_DEPENDENCIES.computeIfAbsent(entry.file, _ -> {
            try (InputStream in = TmTokenizer.class.getResourceAsStream(
                    GRAMMAR_ROOT + entry.file)) {
                if (in == null) return Set.of();
                Set<String> dependencies = new HashSet<>();
                collectExternalIncludes(JsonUtils.getMapper().readTree(in), dependencies);
                dependencies.remove(entry.scope);
                dependencies.retainAll(ENTRIES_BY_SCOPE.keySet());
                return Set.copyOf(dependencies);
            } catch (Exception e) {
                LOG.warn("Failed to inspect TM grammar dependencies '{}': {}",
                    entry.file, e.toString());
                return Set.of();
            }
        });
    }

    private static void collectExternalIncludes(JsonNode node, Set<String> dependencies) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if (Strings.CS.equals("include", field.getKey()) && field.getValue().isTextual()) {
                    String include = field.getValue().asText();
                    if (!include.isEmpty() && include.charAt(0) != '#'
                            && include.charAt(0) != '$') {
                        int repositoryFragment = include.indexOf('#');
                        dependencies.add(repositoryFragment < 0
                            ? include : include.substring(0, repositoryFragment));
                    }
                } else {
                    collectExternalIncludes(field.getValue(), dependencies);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectExternalIncludes(child, dependencies));
        }
    }

    private static void loadIndex() {
        try (InputStream in = TmTokenizer.class.getResourceAsStream(INDEX_RESOURCE)) {
            if (in == null) {
                LOG.warn("Grammar index not found at {} — syntax highlighting disabled.", INDEX_RESOURCE);
                return;
            }
            JsonNode root = JsonUtils.getMapper().readTree(in);
            JsonNode languages = root.path("languages");
            if (!languages.isObject()) {
                LOG.warn("Grammar index missing 'languages' object.");
                return;
            }
            languages.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                String file = v.path("file").asText(null);
                String scope = v.path("scope").asText(null);
                if (file != null && scope != null) {
                    GrammarEntry entry = new GrammarEntry(file, scope);
                    ENTRIES.put(e.getKey().toLowerCase(Locale.ROOT), entry);
                    ENTRIES_BY_SCOPE.putIfAbsent(scope, entry);
                }
            });
            LOG.debug("Loaded {} grammar aliases from index.json", ENTRIES.size());
        } catch (Exception e) {
            LOG.warn("Failed to load grammar index: {}", e.toString());
        }
    }

    /** Test hook — clears caches so tests can re-init. */
    static void resetForTests() {
        synchronized (INIT_LOCK) {
            initialized = false;
            registry = null;
            ENTRIES.clear();
            ENTRIES_BY_SCOPE.clear();
            GRAMMARS.clear();
            GRAMMAR_DEPENDENCIES.clear();
        }
    }

    static int loadedGrammarCountForTests() {
        synchronized (INIT_LOCK) {
            return GRAMMARS.size();
        }
    }

    static int uniqueGrammarCountForTests() {
        ensureInitialized();
        synchronized (INIT_LOCK) {
            return ENTRIES_BY_SCOPE.size();
        }
    }
}
