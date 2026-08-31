package com.claudecode.ui.syntax;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.constants.AnsiStyle;

import com.claudecode.ui.lanterna.theme.RgbColor;

import java.util.*;

/**
 * TextMate scope → terminal RGB mapping.
 */
public final class ScopeColorMap {

    private ScopeColorMap() {}

    // ── Palette tables ────────────────────────────────────────────────────


    /** Monokai Extended foreground colors — used for all *-dark themes (incl. dark-daltonized). */
    private static final Map<String, RgbColor> MONOKAI_SCOPES = Map.<String, RgbColor>ofEntries(
        Map.entry("keyword",                new RgbColor(249, 38, 114)),
        Map.entry("_storage",               new RgbColor(102, 217, 239)),
        Map.entry("built_in",               new RgbColor(166, 226, 46)),
        Map.entry("type",                   new RgbColor(166, 226, 46)),
        Map.entry("literal",                new RgbColor(190, 132, 255)),
        Map.entry("number",                 new RgbColor(190, 132, 255)),
        Map.entry("string",                 new RgbColor(230, 219, 116)),
        Map.entry("title",                  new RgbColor(166, 226, 46)),
        Map.entry("title.function",         new RgbColor(166, 226, 46)),
        Map.entry("title.class",            new RgbColor(166, 226, 46)),
        Map.entry("title.class.inherited",  new RgbColor(166, 226, 46)),
        Map.entry("params",                 new RgbColor(253, 151, 31)),
        Map.entry("comment",                new RgbColor(117, 113, 94)),
        Map.entry("meta",                   new RgbColor(117, 113, 94)),
        Map.entry("attr",                   new RgbColor(166, 226, 46)),
        Map.entry("attribute",              new RgbColor(166, 226, 46)),
        Map.entry("variable",               new RgbColor(255, 255, 255)),
        Map.entry("variable.language",      new RgbColor(255, 255, 255)),
        Map.entry("property",               new RgbColor(255, 255, 255)),
        Map.entry("operator",               new RgbColor(249, 38, 114)),
        Map.entry("punctuation",            new RgbColor(248, 248, 242)),
        Map.entry("symbol",                 new RgbColor(190, 132, 255)),
        Map.entry("regexp",                 new RgbColor(230, 219, 116)),
        Map.entry("subst",                  new RgbColor(248, 248, 242))
    );

    /** GitHub-light foreground colors — used for all *-light themes (incl. light-daltonized). */
    private static final Map<String, RgbColor> GITHUB_SCOPES = Map.<String, RgbColor>ofEntries(
        Map.entry("keyword",                new RgbColor(167, 29, 93)),
        Map.entry("_storage",               new RgbColor(167, 29, 93)),
        Map.entry("built_in",               new RgbColor(0, 134, 179)),
        Map.entry("type",                   new RgbColor(0, 134, 179)),
        Map.entry("literal",                new RgbColor(0, 134, 179)),
        Map.entry("number",                 new RgbColor(0, 134, 179)),
        Map.entry("string",                 new RgbColor(24, 54, 145)),
        Map.entry("title",                  new RgbColor(121, 93, 163)),
        Map.entry("title.function",         new RgbColor(121, 93, 163)),
        Map.entry("title.class",            new RgbColor(0, 0, 0)),
        Map.entry("title.class.inherited",  new RgbColor(0, 0, 0)),
        Map.entry("params",                 new RgbColor(0, 134, 179)),
        Map.entry("comment",                new RgbColor(150, 152, 150)),
        Map.entry("meta",                   new RgbColor(150, 152, 150)),
        Map.entry("attr",                   new RgbColor(0, 134, 179)),
        Map.entry("attribute",              new RgbColor(0, 134, 179)),
        Map.entry("variable",               new RgbColor(0, 134, 179)),
        Map.entry("variable.language",      new RgbColor(0, 134, 179)),
        Map.entry("property",               new RgbColor(0, 134, 179)),
        Map.entry("operator",               new RgbColor(167, 29, 93)),
        Map.entry("punctuation",            new RgbColor(51, 51, 51)),
        Map.entry("symbol",                 new RgbColor(0, 134, 179)),
        Map.entry("regexp",                 new RgbColor(24, 54, 145)),
        Map.entry("subst",                  new RgbColor(51, 51, 51))
    );

    /**
     * Standard 16-color ANSI subset for *-ansi themes.
     */
    private static final Map<String, RgbColor> ANSI_SCOPES = Map.<String, RgbColor>ofEntries(
        Map.entry("keyword",         new RgbColor(255, 85, 255)),  // ANSI 13 magenta_bright
        Map.entry("_storage",        new RgbColor(85, 255, 255)),  // ANSI 14 cyan_bright
        Map.entry("built_in",        new RgbColor(85, 255, 255)),  // ANSI 14
        Map.entry("type",            new RgbColor(85, 255, 255)),  // ANSI 14
        Map.entry("literal",         new RgbColor(85, 85, 255)),   // ANSI 12 blue_bright
        Map.entry("number",          new RgbColor(85, 85, 255)),   // ANSI 12
        Map.entry("string",          new RgbColor(85, 255, 85)),   // ANSI 10 green_bright
        Map.entry("title",           new RgbColor(255, 255, 85)),  // ANSI 11 yellow_bright
        Map.entry("title.function",  new RgbColor(255, 255, 85)),
        Map.entry("title.class",     new RgbColor(255, 255, 85)),
        Map.entry("comment",         new RgbColor(85, 85, 85)),    // ANSI 8 dark gray
        Map.entry("meta",            new RgbColor(85, 85, 85))
    );

    /** Default foreground when no scope matches — per palette. */
    private static final RgbColor MONOKAI_FG = new RgbColor(248, 248, 242);
    private static final RgbColor GITHUB_FG  = new RgbColor(51, 51, 51);
    private static final RgbColor ANSI_FG    = new RgbColor(170, 170, 170);   // ANSI 7 white

    /**
     * Keywords that syntect's Monokai grammar puts under {@code storage.type}
     * (different colour from {@code keyword.control}). TextMate grammars
     * mostly tag these correctly via {@code storage.type.*}, but this set is
     * the fallback for grammars that drop them into plain {@code keyword.*}.
     */
    static final Set<String> STORAGE_KEYWORDS = Set.of(
        "const", "let", "var",
        "function", "class", "type", "interface", "enum",
        "namespace", "module",
        "def", "fn", "func", "struct", "trait", "impl"
    );

    /**
     * Resolve a TextMate scope chain + token text to an RGB color.
     * {@code scopes} comes from {@link org.eclipse.tm4e.core.grammar.IToken#getScopes()}:
     * the root scope (e.g. {@code "source.java"}) is first; the most specific
     * leaf (e.g. {@code "entity.name.function.java"}) is last. We walk from
     * the leaf back so the most-specific scope wins.
     */
    public static RgbColor scopeColor(List<String> scopes, String text, String themeName) {
        Map<String, RgbColor> palette = pickPalette(themeName);
        RgbColor foreground = pickForeground(themeName);
        if (scopes == null || scopes.isEmpty()) return foreground;
        // Walk leaf → root so a more-specific scope wins over an ancestor.
        for (int i = scopes.size() - 1; i >= 0; i--) {
            String tmScope = scopes.get(i);
            String hljsKey = mapTmScopeToHljs(tmScope, text);
            if (hljsKey == null) continue;

            if (Strings.CS.equals("keyword", hljsKey)
                    && text != null && STORAGE_KEYWORDS.contains(text.trim())) {
                RgbColor storage = palette.get("_storage");
                if (storage != null) return storage;
            }
            RgbColor exact = palette.get(hljsKey);
            if (exact != null) return exact;
            int dot = hljsKey.indexOf('.');
            if (dot > 0) {
                RgbColor head = palette.get(hljsKey.substring(0, dot));
                if (head != null) return head;
            }
        }
        return foreground;
    }

    /**
     * Pick the SGR styles (italic / bold / underline) for a scope chain.
     */
    public static EnumSet<AnsiStyle> scopeStyle(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) return EmptyStyle.EMPTY;
        EnumSet<AnsiStyle> styles = EnumSet.noneOf(AnsiStyle.class);
        for (int i = scopes.size() - 1; i >= 0; i--) {
            String s = scopes.get(i);
            if (s == null) continue;
            // Comments are italic across both Monokai and GitHub themes.
            if (Strings.CS.startsWith(s, "comment") || Strings.CS.startsWith(s, "punctuation.definition.comment")) {
                styles.add(AnsiStyle.ITALIC);
            }
            // Markdown bold/italic (when the embedded grammar is markdown).
            if (Strings.CS.startsWith(s, "markup.bold")) styles.add(AnsiStyle.BOLD);
            if (Strings.CS.startsWith(s, "markup.italic")) styles.add(AnsiStyle.ITALIC);
            if (Strings.CS.startsWith(s, "markup.heading")) styles.add(AnsiStyle.BOLD);
            if (Strings.CS.startsWith(s, "markup.underline.link")) styles.add(AnsiStyle.UNDERLINE);
            // Storage modifiers in some themes are italic (Java's
            // `public/private/static`). Skipped — Monokai keeps them upright.
        }
        return styles;
    }

    /** Cached empty set so {@link #scopeStyle} can avoid an allocation per token. */
    private static final class EmptyStyle {
        static final EnumSet<AnsiStyle> EMPTY = EnumSet.noneOf(AnsiStyle.class);
    }


    private static Map<String, RgbColor> pickPalette(String themeName) {
        if (themeName == null) return MONOKAI_SCOPES;
        String n = themeName.toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(n, "ansi")) return ANSI_SCOPES;
        if (Strings.CS.contains(n, "dark")) return MONOKAI_SCOPES;
        return GITHUB_SCOPES;
    }

    private static RgbColor pickForeground(String themeName) {
        if (themeName == null) return MONOKAI_FG;
        String n = themeName.toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(n, "ansi")) return ANSI_FG;
        if (Strings.CS.contains(n, "dark")) return MONOKAI_FG;
        return GITHUB_FG;
    }

    /**
     * Translate a TextMate scope to a hljs-style colour key.
     */
    public static String mapTmScopeToHljs(String tmScope, String text) {
        if (tmScope == null) return null;

        // Comments ────────────────────────────────────────────────────────
        // Comment border punctuation (//, /*, */, #) lives under
        // punctuation.definition.comment in many grammars — bridge it here
        // so opening/closing markers don't drop to the punctuation grey.
        if (Strings.CS.startsWith(tmScope, "punctuation.definition.comment")) return "comment";
        if (Strings.CS.startsWith(tmScope, "comment"))                 return "comment";

        // Strings & their inner pieces ───────────────────────────────────
        // String border punctuation (quotes, here-doc markers) belongs to the
        // string token visually — without this rule, opening/closing quotes
        // appear in the default punctuation colour and break the run.
        if (Strings.CS.startsWith(tmScope, "punctuation.definition.string"))    return "string";
        if (Strings.CS.startsWith(tmScope, "punctuation.definition.template-expression")) return "subst";
        if (Strings.CS.startsWith(tmScope, "string.regexp"))           return "regexp";
        if (Strings.CS.startsWith(tmScope, "string.interpolated"))     return "subst";
        if (Strings.CS.startsWith(tmScope, "string.template"))         return "string";
        if (Strings.CS.startsWith(tmScope, "punctuation.section.embedded")) return "subst";
        if (Strings.CS.startsWith(tmScope, "string"))                  return "string";
        if (Strings.CS.startsWith(tmScope, "meta.embedded"))           return "subst";

        // Numbers / literals ─────────────────────────────────────────────
        if (Strings.CS.startsWith(tmScope, "constant.numeric"))        return "number";
        if (Strings.CS.startsWith(tmScope, "constant.language"))       return "literal";
        if (Strings.CS.startsWith(tmScope, "constant.character.escape")) return "string";
        if (Strings.CS.startsWith(tmScope, "constant.character"))      return "literal";
        if (Strings.CS.startsWith(tmScope, "constant.other.symbol"))   return "symbol";
        if (Strings.CS.startsWith(tmScope, "constant.other.placeholder")) return "subst";
        if (Strings.CS.startsWith(tmScope, "constant.other"))          return "literal";

        // Identifiers (functions / classes / types) ──────────────────────
        if (Strings.CS.startsWith(tmScope, "entity.name.function"))    return "title.function";
        if (Strings.CS.startsWith(tmScope, "entity.name.method"))      return "title.function";
        if (Strings.CS.startsWith(tmScope, "entity.name.type.class"))  return "title.class";
        if (Strings.CS.startsWith(tmScope, "entity.name.class"))       return "title.class";
        if (Strings.CS.startsWith(tmScope, "entity.name.section"))     return "title";
        if (Strings.CS.startsWith(tmScope, "entity.name.type"))        return "type";
        if (Strings.CS.startsWith(tmScope, "entity.name.tag"))         return "attr";
        if (Strings.CS.startsWith(tmScope, "entity.name.label"))       return "attr";
        if (Strings.CS.startsWith(tmScope, "entity.other.attribute-name")) return "attribute";
        if (Strings.CS.startsWith(tmScope, "entity.other.inherited-class")) return "title.class.inherited";

        // Keywords ───────────────────────────────────────────────────────
        if (Strings.CS.startsWith(tmScope, "keyword.operator.new"))    return "keyword";
        if (Strings.CS.startsWith(tmScope, "keyword.operator.expression")) return "keyword";
        if (Strings.CS.startsWith(tmScope, "keyword.operator"))        return "operator";
        if (Strings.CS.startsWith(tmScope, "keyword.control"))         return "keyword";
        if (Strings.CS.startsWith(tmScope, "keyword.other.unit"))      return "number";
        if (Strings.CS.startsWith(tmScope, "keyword.other.directive")) return "meta";
        if (Strings.CS.startsWith(tmScope, "keyword"))                 return "keyword";

        // Storage (declaration keywords + modifiers) ──────────────────────
        if (Strings.CS.startsWith(tmScope, "storage.type.annotation")) return "meta";
        if (Strings.CS.startsWith(tmScope, "storage.type.function.arrow")) return "operator";
        if (Strings.CS.startsWith(tmScope, "storage.type"))            return "_storage";
        if (Strings.CS.startsWith(tmScope, "storage.modifier"))        return "keyword";
        if (Strings.CS.startsWith(tmScope, "storage"))                 return "keyword";

        // Variables ──────────────────────────────────────────────────────
        if (Strings.CS.startsWith(tmScope, "variable.parameter"))      return "params";
        if (Strings.CS.startsWith(tmScope, "variable.language"))       return "variable.language";
        if (Strings.CS.startsWith(tmScope, "variable.other.property")) return "property";
        if (Strings.CS.startsWith(tmScope, "variable.other.constant")) return "literal";
        if (Strings.CS.startsWith(tmScope, "variable.function"))       return "title.function";
        if (Strings.CS.startsWith(tmScope, "variable"))                return "variable";

        // Support (built-in functions / types / variables / constants) ────
        if (Strings.CS.startsWith(tmScope, "support.function"))        return "built_in";
        if (Strings.CS.startsWith(tmScope, "support.method"))          return "built_in";
        if (Strings.CS.startsWith(tmScope, "support.class"))           return "built_in";
        if (Strings.CS.startsWith(tmScope, "support.type"))            return "type";
        if (Strings.CS.startsWith(tmScope, "support.constant"))        return "built_in";
        if (Strings.CS.startsWith(tmScope, "support.variable"))        return "variable.language";
        if (Strings.CS.startsWith(tmScope, "support.other"))           return "built_in";

        // Meta / decorators / annotations ────────────────────────────────
        if (Strings.CS.startsWith(tmScope, "meta.decorator"))          return "meta";
        if (Strings.CS.startsWith(tmScope, "meta.annotation"))         return "meta";
        if (Strings.CS.startsWith(tmScope, "meta.tag"))                return "keyword";
        if (Strings.CS.startsWith(tmScope, "meta.preprocessor"))       return "meta";
        if (Strings.CS.startsWith(tmScope, "meta.attribute"))          return "attribute";
        if (Strings.CS.startsWith(tmScope, "meta.directive"))          return "meta";

        // Punctuation ────────────────────────────────────────────────────
        if (Strings.CS.startsWith(tmScope, "punctuation"))             return "punctuation";

        // Markdown / markup ──────────────────────────────────────────────
        if (Strings.CS.startsWith(tmScope, "markup.heading"))          return "title";
        if (Strings.CS.startsWith(tmScope, "markup.bold"))             return "keyword";
        if (Strings.CS.startsWith(tmScope, "markup.italic"))           return "string";
        if (Strings.CS.startsWith(tmScope, "markup.underline.link"))   return "variable";
        if (Strings.CS.startsWith(tmScope, "markup.underline"))        return "string";
        if (Strings.CS.startsWith(tmScope, "markup.inserted"))         return "string";
        if (Strings.CS.startsWith(tmScope, "markup.deleted"))          return "number";
        if (Strings.CS.startsWith(tmScope, "markup.changed"))          return "params";
        if (Strings.CS.startsWith(tmScope, "markup.quote"))            return "comment";
        if (Strings.CS.startsWith(tmScope, "markup.raw"))              return "string";
        if (Strings.CS.startsWith(tmScope, "markup.list"))             return "punctuation";
        if (Strings.CS.startsWith(tmScope, "markup.fenced_code"))      return "string";
        if (Strings.CS.startsWith(tmScope, "markup"))                  return "string";

        // Invalid / illegal — highlight as keyword (pink-ish in Monokai) so
        // grammar errors stand out instead of vanishing into the default fg.
        if (Strings.CS.startsWith(tmScope, "invalid.deprecated"))      return "comment";
        if (Strings.CS.startsWith(tmScope, "invalid"))                 return "keyword";

        // Unknown — let caller try the next scope up the chain.
        return null;
    }
}
