package com.claudecode.core.prompt;

import org.apache.commons.lang3.StringUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for substituting $ARGUMENTS placeholders in skill/command prompts.
 */
public final class ArgumentSubstitutor {

    /**
     * Pattern template for named argument substitution.
     */
    private static final String NAMED_ARG_PATTERN_TEMPLATE = "\\$%s(?![\\[\\w])";
    private static final Pattern INDEXED_ARGS_PATTERN = Pattern.compile("\\$ARGUMENTS\\[(\\d+)]");
    private static final Pattern POSITIONAL_PATTERN = Pattern.compile("\\$(\\d+)(?!\\w)");
    private static final String BARE_ARGUMENTS = "$ARGUMENTS";

    private ArgumentSubstitutor() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Parses an arguments string into an array of individual arguments using shell-quote tokenisation
     * (respects quoted groups).
     */
    public static List<String> parseArguments(String rawArgs) {
        if (StringUtils.isBlank(rawArgs)) {
            return Collections.emptyList();
        }

        // Shell-quote tokenisation. On failure (signalled by tryParseShellTokens
        // returning null — e.g. an unterminated quote) fall back to a lossless


        // character-preserving result instead of silently dropping characters.
        List<String> result = tryParseShellTokens(rawArgs);
        if (result != null) {
            return result;
        }
        List<String> fallback = new ArrayList<>();
        for (String token : rawArgs.split("\\s+")) {
            if (!token.isEmpty()) {
                fallback.add(token);
            }
        }
        return Collections.unmodifiableList(fallback);
    }

    /**
     * Parses argument names from the frontmatter {@code arguments} field.
     * Accepts either a space-separated {@link String} or a {@link List} of strings.
     * Purely-numeric names are filtered out because they conflict with the
     * {@code $0}, {@code $1} positional shorthand.
     *
     * <p>Examples:
     * <pre>
     *   "foo bar baz"        -> ["foo", "bar", "baz"]
     *   ["foo", "bar", "baz"] -> ["foo", "bar", "baz"]
     *   null                 -> []
     * </pre>
     *
     * @param frontmatterValue the raw value from YAML/frontmatter; may be null,
     *                         a {@link String}, or a {@link List}
     * @return validated argument name tokens, never null
     */
    @SuppressWarnings("unchecked")
    public static List<String> parseArgumentNames(Object frontmatterValue) {
      switch (frontmatterValue) {
        case null -> {
          return Collections.emptyList();
        }
        case List<?> list -> {
          List<String> result = new ArrayList<>();
          for (Object item : list) {
            if (item instanceof String s && isValidArgumentName(s)) {
              result.add(s);
            }
          }
          return Collections.unmodifiableList(result);
        }
        case String s -> {
          List<String> result = new ArrayList<>();
          for (String token : s.split("\\s+")) {
            if (isValidArgumentName(token)) {
              result.add(token);
            }
          }
          return Collections.unmodifiableList(result);
        }
        default -> {
        }
      }
      return Collections.emptyList();
    }

    /**
     * Generates an argument hint showing the remaining unfilled argument names.
     *
     * <p>Examples:
     * <pre>
     *   argNames=["a","b","c"], typedArgs=["x"]   -> Optional.of("[b] [c]")
     *   argNames=["a","b"],     typedArgs=["x","y"] -> Optional.empty
     *   argNames=[],            typedArgs=[]         -> Optional.empty
     * </pre>
     *
     * @param argNames   all declared argument names from frontmatter
     * @param typedArgs  arguments already typed by the user
     * @return hint string like {@code "[name1] [name2]"}, or empty when all filled
     */
    public static Optional<String> generateProgressiveArgumentHint(
            List<String> argNames, List<String> typedArgs) {
        int filled = typedArgs == null ? 0 : typedArgs.size();
        List<String> remaining = argNames == null
                ? Collections.emptyList()
                : argNames.subList(Math.min(filled, argNames.size()), argNames.size());
        if (remaining.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < remaining.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append('[').append(remaining.get(i)).append(']');
        }
        return Optional.of(sb.toString());
    }

    /**
     * Substitutes all {@code $ARGUMENTS} placeholder variants in {@code content} with values derived
     * from {@code rawArgs}.
     */
    public static String substitute(
            String content,
            String rawArgs,
            List<String> argumentNames,
            boolean appendIfNoPlaceholder) {

        // null/undefined means no args provided — return content unchanged
        if (rawArgs == null) {
            return content;
        }

        List<String> parsedArgs = parseArguments(rawArgs);
        List<String> names = argumentNames == null ? Collections.emptyList() : argumentNames;
        String result = content;

        // 1. Named argument substitution: $foo -> parsedArgs[index_of_name]
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (StringUtils.isEmpty(name)) continue;
            String replacement = (i < parsedArgs.size()) ? parsedArgs.get(i) : "";
            Pattern namedPattern = Pattern.compile(
                    String.format(NAMED_ARG_PATTERN_TEMPLATE, Pattern.quote(name)));
            result = namedPattern.matcher(result).replaceAll(
                    Matcher.quoteReplacement(replacement));
        }

        // 2. Indexed: $ARGUMENTS[N] -> parsedArgs[N]
        {
            Matcher m = INDEXED_ARGS_PATTERN.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                int idx = Integer.parseInt(m.group(1));
                String val = (idx < parsedArgs.size()) ? parsedArgs.get(idx) : "";
                m.appendReplacement(sb, Matcher.quoteReplacement(val));
            }
            m.appendTail(sb);
            result = sb.toString();
        }


        {
            Matcher m = POSITIONAL_PATTERN.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                int idx = Integer.parseInt(m.group(1));
                String val = (idx < parsedArgs.size()) ? parsedArgs.get(idx) : "";
                m.appendReplacement(sb, Matcher.quoteReplacement(val));
            }
            m.appendTail(sb);
            result = sb.toString();
        }

        // 4. Bare $ARGUMENTS -> rawArgs
        result = result.replace(BARE_ARGUMENTS, rawArgs);

        // 5. Append if no placeholder was found and appendIfNoPlaceholder is true
        //    Only append when rawArgs is non-empty (empty string = command with no args)
        if (result.equals(content) && appendIfNoPlaceholder && !rawArgs.isEmpty()) {
            result = result + "\n\nARGUMENTS: " + rawArgs;
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * A name is valid when it is non-empty, non-blank, and not purely numeric.
     * Purely-numeric names conflict with the {@code $0/$1} positional shorthand.
     */
    private static boolean isValidArgumentName(String name) {
        if (StringUtils.isBlank(name)) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return false;

        for (char c : trimmed.toCharArray()) {
            if (!Character.isDigit(c)) return true;
        }
        return false; // all digits
    }


    static List<String> tryParseShellTokens(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;

        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);

            if (c == '"') {
                // Double-quoted string
                inToken = true;
                i++;
                boolean closed = false;
                while (i < input.length()) {
                    char dc = input.charAt(i);
                    if (dc == '"') {
                        i++;
                        closed = true;
                        break;
                    } else if (dc == '\\' && i + 1 < input.length()) {
                        char next = input.charAt(i + 1);

                        // For \$: consume the backslash, append just '$' (bare dollar).
                        // For any other char after \: append the backslash literally then
                        // let the next iteration handle the char.
                        if (next == '"' || next == '\\' || next == '$') {
                            current.append(next);
                            i += 2;
                        } else {
                            current.append(dc);
                            i++;
                        }
                    } else {
                        current.append(dc);
                        i++;
                    }
                }
                // Unterminated quote → tokenisation failed; signal parseArguments to
                // fall back to a lossless whitespace split (audit gap args_007).
                if (!closed) return null;
            } else if (c == '\'') {
                // Single-quoted string — no escapes inside
                inToken = true;
                i++;
                boolean closed = false;
                while (i < input.length()) {
                    char sc = input.charAt(i);
                    if (sc == '\'') {
                        i++;
                        closed = true;
                        break;
                    }
                    current.append(sc);
                    i++;
                }
                if (!closed) return null;
            } else if (c == '\\') {
                // Backslash escape outside quotes.
                // If there is a next char, consume both and append the escaped char.
                // If the backslash is at EOF (trailing backslash), discard it per

                // appends, so the lone backslash disappears).
                if (i + 1 < input.length()) {
                    inToken = true;
                    current.append(input.charAt(i + 1));
                    i += 2;
                } else {
                    // Trailing backslash at EOF — consume and discard (args_006).
                    // Do NOT set inToken here so that a lone backslash produces no token.
                    i++;
                }
            } else if (c == '|' || c == ';' || c == '&' || c == '<' || c == '>'
                    || c == '(' || c == ')' || c == '!') {
                // Shell metacharacter operators (args_001 fix).
                // npm shell-quote emits these as non-string {op:...} objects;

                // `typeof token === 'string'`. Flush any current token and skip.
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
                i++;
            } else if (Character.isWhitespace(c)) {
                // Word boundary
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
                i++;
            } else {
                // Regular character (includes *, ?, # — see Javadoc for intentional

                inToken = true;
                current.append(c);
                i++;
            }
        }

        // Flush last token
        if (inToken) {
            tokens.add(current.toString());
        }

        return Collections.unmodifiableList(tokens);
    }
}
